package com.aphex3k.media.immich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aphex3k.eo1.AuthenticationFailedException;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The session store's job: one token per account, one login per token, and a renewal that is
 * visible in the store rather than remembered only by whichever client happened to log in.
 */
public class ImmichSessionStoreTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** A JWT-shaped token of the same shape Immich hands back. */
    private static String tokenExpiringIn(long seconds) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(UTF_8)) + "."
                + enc.encodeToString(( "{\"exp\":"
                        + ((System.currentTimeMillis() / 1000L) + seconds) + "}")
                        .getBytes(UTF_8))
                + ".sig";
    }

    @Before
    public void resetStore() {
        ImmichSessionStore.clearForTests();
    }

    @Test
    public void oneAccountHasOneSessionSharedByEveryClient() {
        ImmichSessionStore store = ImmichSessionStore.get();
        ImmichSessionStore.Session first = store.attach("https://a.example/", "u@a.io", "pw");
        ImmichSessionStore.Session second = store.attach("https://a.example/", "u@a.io", "pw");

        assertSame("a rebuilt client must land on the live session, not a new one", first, second);
    }

    @Test
    public void differentAccountsAndHostsDoNotShareASession() {
        ImmichSessionStore store = ImmichSessionStore.get();
        assertNotSame(store.attach("https://a.example/", "u@a.io", "pw"),
                store.attach("https://a.example/", "other@a.io", "pw"));
        assertNotSame(store.attach("https://a.example/", "u@a.io", "pw"),
                store.attach("https://b.example/", "u@a.io", "pw"));
    }

    /**
     * The regression this whole change exists for: a pool rebuild news a backend per tick, and a
     * session owned by the client meant a login per tick.
     */
    @Test
    public void aHeldTokenIsReusedWithoutAnotherLogin() throws Exception {
        final AtomicInteger logins = new AtomicInteger();
        ImmichSessionStore.Session session =
                ImmichSessionStore.get().attach("https://a.example/", "u@a.io", "pw");

        for (int i = 0; i < 3; i++) {
            assertNotNull(session.token(new ImmichSessionStore.LoginRunner() {
                @Override
                public String run() {
                    logins.incrementAndGet();
                    return tokenExpiringIn(3600L);
                }
            }));
        }
        assertEquals("one token, one login", 1, logins.get());
    }

    @Test
    public void aTokenAtTheEndOfItsLifeIsReplacedBeforeTheNextRequest() throws Exception {
        final AtomicInteger logins = new AtomicInteger();
        ImmichSessionStore.Session session =
                ImmichSessionStore.get().attach("https://a.example/", "u@a.io", "pw");
        session.token(new ImmichSessionStore.LoginRunner() {
            @Override
            public String run() {
                logins.incrementAndGet();
                return tokenExpiringIn(30L);
            }
        });
        assertEquals(1, logins.get());

        // Inside the store's refresh margin, so "still in the future" is not "still usable".
        session.token(new ImmichSessionStore.LoginRunner() {
            @Override
            public String run() {
                logins.incrementAndGet();
                return tokenExpiringIn(3600L);
            }
        });
        assertEquals("a token expiring in 30s must be renewed proactively", 2, logins.get());
    }

    /**
     * Rotation, prefetch and tagging all reach one session; two logins racing would add two server
     * session rows and one of them would win at random.
     */
    /** Widens the window a second caller could otherwise race into. */
    private static void holdOtherThreadsOut() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void concurrentCallersPerformExactlyOneLogin() throws Exception {
        final int threads = 8;
        final AtomicInteger logins = new AtomicInteger();
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final ImmichSessionStore.Session session =
                ImmichSessionStore.get().attach("https://a.example/", "u@a.io", "pw");

        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        session.token(new ImmichSessionStore.LoginRunner() {
                            @Override
                            public String run() {
                                logins.incrementAndGet();
                                holdOtherThreadsOut();
                                return tokenExpiringIn(3600L);
                            }
                        });
                    } catch (Exception e) {
                        fail(e.toString());
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        go.countDown();
        assertTrue("worker threads did not finish", done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, logins.get());
    }

    @Test
    public void aFailedLoginCoolsDownInsteadOfRetryingEveryCall() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        ImmichSessionStore.Session session =
                ImmichSessionStore.get().attach("https://a.example/", "u@a.io", "wrong");

        for (int i = 0; i < 2; i++) {
            try {
                session.token(new ImmichSessionStore.LoginRunner() {
                    @Override
                    public String run() throws AuthenticationFailedException {
                        attempts.incrementAndGet();
                        throw new AuthenticationFailedException(401);
                    }
                });
                fail("a rejected login must not hand back a token");
            } catch (AuthenticationFailedException expected) {
                // first attempt: the server's own answer
            } catch (IOException cooledDown) {
                assertTrue(cooledDown.getMessage(), cooledDown.getMessage().contains("cooling down"));
            }
        }
        assertEquals("the retry must be refused before it reaches the server", 1, attempts.get());
    }

    /** Editing a password has to take effect on the next tick, not wait out a cooldown. */
    @Test
    public void aChangedPasswordStartsAFreshSession() throws Exception {
        ImmichSessionStore store = ImmichSessionStore.get();
        final AtomicInteger logins = new AtomicInteger();
        ImmichSessionStore.Session before = store.attach("https://a.example/", "u@a.io", "old");
        before.token(new ImmichSessionStore.LoginRunner() {
            @Override
            public String run() {
                logins.incrementAndGet();
                return tokenExpiringIn(3600L);
            }
        });

        ImmichSessionStore.Session after = store.attach("https://a.example/", "u@a.io", "new");
        assertNotSame("a new password cannot inherit the old token", before, after);
        after.token(new ImmichSessionStore.LoginRunner() {
            @Override
            public String run() {
                logins.incrementAndGet();
                return tokenExpiringIn(3600L);
            }
        });
        assertEquals(2, logins.get());
    }
}
