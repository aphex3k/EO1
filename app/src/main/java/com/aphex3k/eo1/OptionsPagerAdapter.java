package com.aphex3k.eo1;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class OptionsPagerAdapter extends FragmentStateAdapter {
    private final Fragment[] fragments;
    public OptionsPagerAdapter(@NonNull Fragment parent, Fragment... fragments) {
        super(parent);
        this.fragments = fragments;
    }
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return fragments[position];
    }
    @Override
    public int getItemCount() {
        return fragments.length;
    }
}
