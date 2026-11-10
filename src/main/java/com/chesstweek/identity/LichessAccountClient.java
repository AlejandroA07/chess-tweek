package com.chesstweek.identity;

interface LichessAccountClient {

    LichessAccount currentAccount(String accessToken);
}
