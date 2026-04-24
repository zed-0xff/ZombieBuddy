package me.zed_0xff.zombie_buddy.patches.experimental.http;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.zed_0xff.zombie_buddy.patches.experimental.HttpServer;
import me.zed_0xff.zombie_buddy.ZombieBuddy;

public class VersionHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpServer.logRequest(exchange);
        String response = ZombieBuddy.getFullVersionString() + "\n";
        HttpServer.sendResponse(exchange, 200, response);
    }
}
