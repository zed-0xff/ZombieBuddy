package me.zed_0xff.zombie_buddy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared Gson configuration for on-disk and IPC JSON. */
final class ZBGson {

    private ZBGson() {}

    /** Pretty-printed output ({@link JarBatchApprovalProtocol}, {@link JavaModApprovalsStore}). */
    static final Gson PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
}
