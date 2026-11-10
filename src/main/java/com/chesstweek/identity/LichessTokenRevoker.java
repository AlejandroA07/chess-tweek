package com.chesstweek.identity;

interface LichessTokenRevoker {

    void revoke(String accessToken);
}
