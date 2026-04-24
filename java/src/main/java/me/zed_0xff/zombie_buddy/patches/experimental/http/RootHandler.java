package me.zed_0xff.zombie_buddy.patches.experimental.http;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.zed_0xff.zombie_buddy.patches.experimental.HttpServer;

public class RootHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpServer.logRequest(exchange);
        String response = "ZombieBuddy HTTP Server\n\nEndpoints:\n  /status - server status\n  /version - version info\n  /lua - POST lua code to execute (if returns job_*, waits for async completion)\n  /log - GET last log lines (?lines=N, default 100)\n";
        HttpServer.sendResponse(exchange, 200, response);
    }
}
