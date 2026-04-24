package me.zed_0xff.zombie_buddy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/** Shared Gson configuration for on-disk and IPC JSON. */
public final class ZBGson {

    private ZBGson() {}

    private static final TypeAdapter<JavaModInfo.WorkshopItemID> WORKSHOP_ID_ADAPTER = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, JavaModInfo.WorkshopItemID value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.value());
            }
        }

        @Override
        public JavaModInfo.WorkshopItemID read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return new JavaModInfo.WorkshopItemID(in.nextLong());
        }
    };

    private static final TypeAdapter<SteamID64> STEAM_ID64_ADAPTER = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, SteamID64 value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.value());
            }
        }

        @Override
        public SteamID64 read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return new SteamID64(in.nextLong());
        }
    };

    /** Pretty-printed output ({@link JarBatchApprovalProtocol}, {@link JavaModApprovalsStore}). */
    public static final Gson PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .registerTypeAdapter(JavaModInfo.WorkshopItemID.class, WORKSHOP_ID_ADAPTER)
        .registerTypeAdapter(SteamID64.class, STEAM_ID64_ADAPTER)
        .create();
}
