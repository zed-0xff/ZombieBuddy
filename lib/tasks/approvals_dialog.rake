# frozen_string_literal: true

require "json"

ZB_MAIN = "me.zed_0xff.zombie_buddy.frontend.BatchJarApprovalMain"
ZB_BATCH_HEADER = "ZB_BATCH_V6"

def zb_find_java
  if (h = ENV["JAVA_HOME"]) && !h.empty?
    exe = File.join(h, "bin/java")
    return exe if File.executable?(exe)
  end
  return "java" if system("command -v java >/dev/null 2>&1")
  raise "java not found; set JAVA_HOME or install a JDK"
end

def zb_json_entry(h)
  out = {
    "modKey"          => h[:mod_key],
    "modId"           => h[:mod_id],
    "jarAbsolutePath" => h[:jar_path].to_s,
    "sha256"          => h[:sha256].to_s,
    "modifiedHuman"   => h[:modified].to_s,
    "priorHint"       => h[:prior_hint].to_s,
    "modDisplayName"  => h[:mod_display_name].to_s,
    "zbsValid"        => h[:zbs_valid].to_s,
    "zbsNotice"       => h[:zbs_notice].to_s,
    "steamBanStatus"  => h[:steam_ban_status].to_s,
    "steamBanReason"  => h[:steam_ban_reason].to_s,
  }
  wid = h[:workshop_item_id]
  out["workshopItemId"] = wid.to_i if wid
  zid = h[:zbs_steam_id]
  out["zbsSteamId"] = zid.to_i if zid && !zid.to_s.strip.empty?
  out
end

def zb_sample_batch_request_v6_json(entries)
  JSON.pretty_generate(
    "header" => ZB_BATCH_HEADER,
    "entries" => entries.map { |e| zb_json_entry(e) }
  )
end

namespace :zb do
  desc "Run BatchJarApprovalMain with a sample request"
  task :approvals_dialog do
    require "tmpdir"

    authors = JSON.load_file("authors.json")
    abort "authors.yml: expected a mapping with at least one entry" unless authors.is_a?(Array) && authors.any?

    # Keys are SteamID64
    first_id = authors.first["id"]
    jar = "java/build/libs/ZombieBuddy.jar"
    abort "missing #{jar} (build it first)" unless File.file?(jar)

    java = zb_find_java
    hex64 = "a" * 64

    sample = 3.times.map do |i|
      zid = case i
            when 0 then first_id
            when 1 then 76561198000000001
            else 76561198000000002
            end
      label = i.zero? ? "Signed OK (authors.yml)" : "Signed OK"
      {
        mod_key: "DemoModOk#{i}",
        mod_id: "DemoModOk#{i}",
        workshop_item_id: 3_000_000_000_001 + i,
        jar_path: "/tmp/DemoModOk#{i}/media/java/client/DemoModOk.jar",
        sha256: hex64,
        modified: "2026-01-01",
        prior_hint: "",
        mod_display_name: label,
        zbs_valid: "yes",
        zbs_steam_id: zid,
        zbs_notice: "",
        steam_ban_status: "no",
        steam_ban_reason: ""
      }
    end + [
      {
        mod_key: "DemoModBad",
        mod_id: "DemoModBad",
        jar_path: "/tmp/DemoModBad/media/java/client/DemoModBad.jar",
        sha256: "b" * 64,
        modified: "2026-01-02",
        prior_hint: "",
        mod_display_name: "Tampered",
        zbs_valid: "no",
        zbs_steam_id: "",
        zbs_notice: "Invalid signature — JAR may have been tampered with.",
        steam_ban_status: "no",
        steam_ban_reason: ""
      },
      {
        mod_key: "DemoModBanned",
        mod_id: "DemoModBanned",
        workshop_item_id: 3_000_000_000_100,
        jar_path: "/tmp/DemoModBanned/media/java/client/DemoModBanned.jar",
        sha256: "d" * 64,
        modified: "2026-01-03",
        prior_hint: "",
        mod_display_name: "Banned on Workshop",
        zbs_valid: "yes",
        zbs_steam_id: 76561198000001000,
        zbs_notice: "",
        steam_ban_status: "yes",
        steam_ban_reason: "Steam moderation flag."
      },
      {
        mod_key: "DemoModUnknownBan",
        mod_id: "DemoModUnknownBan",
        workshop_item_id: 3_000_000_000_101,
        jar_path: "/tmp/DemoModUnknownBan/media/java/client/DemoModUnknownBan.jar",
        sha256: "e" * 64,
        modified: "2026-01-04",
        prior_hint: "",
        mod_display_name: "Ban status unknown",
        zbs_valid: "yes",
        zbs_steam_id: 76561198000001001,
        zbs_notice: "",
        steam_ban_status: "unknown",
        steam_ban_reason: "Steam API request failed (HTTP 503)."
      },
      {
        mod_key: "DemoModLegacy",
        mod_id: "DemoModLegacy",
        jar_path: "/tmp/DemoModLegacy/media/java/client/DemoModLegacy.jar",
        sha256: "c" * 64,
        modified: "2024-12-01",
        prior_hint: "",
        mod_display_name: "No ZBS fields",
        zbs_valid: "",
        zbs_steam_id: "",
        zbs_notice: "",
        steam_ban_status: "unknown",
        steam_ban_reason: "No workshop id found in path."
      }
    ]

    Dir.mktmpdir("zb-approval-dialog-") do |dir|
      req = File.join(dir, "request.json")
      resp = File.join(dir, "response.json")
      File.write(req, zb_sample_batch_request_v6_json(sample))

      cmd = [ java, "-Djava.awt.headless=false", "-cp", jar, ZB_MAIN, req, resp ]
      puts cmd.join(" ")
      system(*cmd)
      st = $?.exitstatus
      if st != 0
        warn "BatchJarApprovalMain exited #{st} (cancel = 2)"
        exit st if st
      end
      if File.exist?(resp)
        puts "--- response (#{resp}) ---"
        puts File.read(resp)
      else
        warn "No response file at #{resp}"
      end
    end
  end
end
