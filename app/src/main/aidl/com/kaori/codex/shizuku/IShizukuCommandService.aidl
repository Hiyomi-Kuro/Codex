package com.kaori.codex.shizuku;

interface IShizukuCommandService {
    String execute(String command) = 1;
    void destroy() = 16777114;
}
