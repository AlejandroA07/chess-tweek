package com.chesstweek.identity;

interface LichessTokenClient {

    LichessToken exchange(String code, String verifier);
}
