package me.zed_0xff.zombie_buddy.patches.experimental.http;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.zed_0xff.zombie_buddy.patches.experimental.HttpServer;
import zombie.ZomboidFileSystem;

public class LogHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpServer.logRequest(exchange);
        int lines = HttpServer.parseIntParam(exchange.getRequestURI().getQuery(), "lines", 100);
        String logPath = ZomboidFileSystem.instance.getCacheDir() + "/console.txt";

        try {
            String content = tailFile(logPath, lines);
            HttpServer.sendResponse(exchange, 200, content);
        } catch (Exception e) {
            HttpServer.sendResponse(exchange, 500, "Error reading log: " + e.getMessage() + "\n");
        }
    }

    private String tailFile(String path, int lines) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            long fileLength = file.length();
            if (fileLength == 0) {
                return "";
            }

            List<String> result = new ArrayList<>();
            StringBuilder currentLine = new StringBuilder();
            long pos = fileLength - 1;

            while (pos >= 0 && result.size() < lines) {
                file.seek(pos);
                int ch = file.read();
                if (ch == '\n') {
                    if (currentLine.length() > 0) {
                        result.add(currentLine.reverse().toString());
                        currentLine = new StringBuilder();
                    }
                } else if (ch != '\r') {
                    currentLine.append((char) ch);
                }
                pos--;
            }

            if (currentLine.length() > 0 && result.size() < lines) {
                result.add(currentLine.reverse().toString());
            }

            Collections.reverse(result);
            return String.join("\n", result) + "\n";
        }
    }
}
