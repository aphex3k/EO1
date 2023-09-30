package com.aphex3k.giteaApi;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.eo1.ApiServiceGenerator;
import com.aphex3k.eo1.BuildConfig;
import com.vdurmont.semver4j.Semver;

import org.junit.Before;
import org.junit.Ignore;

import java.util.List;

import retrofit2.Response;

@Ignore
public class GiteaApiServiceTest {
    private GiteaApiService apiService;
    @Before
    public void instantiate() {
        this.apiService = ApiServiceGenerator.createService(GiteaApiService.class, "https://gitea.codingmerc.com/");
    }

    @org.junit.Test
    public void getReleases() throws Exception {

        Response<List<GiteaApiGetReleasesResponse>> response = apiService.getReleases("michael", "EO1").execute();

        assertNotNull(response);
        assertTrue(response.isSuccessful());

        List<GiteaApiGetReleasesResponse> body = response.body();

        assertNotNull(body);
    }

    @org.junit.Test
    public void updateAvailable() {

        Semver self = new Semver(BuildConfig.VERSION_NAME);
        Semver latest = new Semver(Integer.MAX_VALUE + ".0.0");

        assertTrue(latest.isGreaterThan(self));
    }
}
