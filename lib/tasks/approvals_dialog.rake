# frozen_string_literal: true

require "json"

ZB_SWING_APPROVAL_MAIN = "me.zed_0xff.zombie_buddy.frontend.SwingApprovalMain"
ZB_IMGUI_APPROVAL_MAIN = "me.zed_0xff.zombie_buddy.frontend.ImguiApprovalMain"
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

def zbs_signed(author)
  {
    zbs_valid: "yes",
    zbs_steam_id: author.fetch("id"),
    zbs_notice: author.fetch("name")
  }
end

def zbs_invalid(notice)
  {
    zbs_valid: "no",
    zbs_steam_id: "",
    zbs_notice: notice
  }
end

def zbs_unsigned
  {
    zbs_valid: "unsigned",
    zbs_steam_id: "",
    zbs_notice: ""
  }
end

def zb_sample_approval_entries
  authors = JSON.load_file("authors.json")
  abort "authors.yml: expected a mapping with at least one entry" unless authors.is_a?(Array) && authors.any?

  first_author = authors.first
  hex64 = "a" * 64

  mods = {
    3619862853 => "ZombieBuddy",
    3709229404 => "Zed's Better ModList",
    3677147974 => "Zed's Universal Mod Unbork",
  }.to_a
  

  3.times.map do |i|
    {
      mod_key: "DemoModOk#{i}_key",
      mod_id: "DemoModOk#{i}_id",
      workshop_item_id: mods[i][0],
      jar_path: "/tmp/DemoModOk#{i}/media/java/client/DemoModOk.jar",
      sha256: hex64,
      modified: "2026-01-01",
      prior_hint: "",
      mod_display_name: mods[i][1],
      steam_ban_status: "no",
      steam_ban_reason: "",
      **zbs_signed(first_author)
    }
  end + [
      {
        mod_key: "DemoModBad_key",
        mod_id: "DemoModBad_id",
        jar_path: "/tmp/DemoModBad/media/java/client/DemoModBad.jar",
        sha256: "b" * 64,
        modified: "2026-01-02",
        prior_hint: "",
        mod_display_name: "Tampered",
        steam_ban_status: "no",
        steam_ban_reason: "",
        **zbs_invalid("Invalid signature - JAR may have been tampered with.")
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
        steam_ban_status: "yes",
        steam_ban_reason: "Steam moderation flag.",
        **zbs_signed("id" => 76561198000001000, "name" => "Banned Author")
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
        steam_ban_status: "unknown",
        steam_ban_reason: "Steam API request failed (HTTP 503).",
        **zbs_signed("id" => 76561198000001001, "name" => "Unknown Ban Author")
      },
      {
        mod_key: "DemoModLegacy",
        mod_id: "DemoModLegacy",
        jar_path: "/tmp/DemoModLegacy/media/java/client/DemoModLegacy.jar",
        sha256: "c" * 64,
        modified: "2024-12-01",
        prior_hint: "",
        mod_display_name: "No ZBS fields",
        steam_ban_status: "unknown",
        steam_ban_reason: "No workshop id found in path.",
        **zbs_unsigned
      }
    ]
end

def zb_run_approval_dialog(main_class, cp: [], props: {}, headless: )
  require "tmpdir"

  jar = "java/build/libs/ZombieBuddy.jar"
  abort "missing #{jar} (build it first)" unless File.file?(jar)
  cp << jar
  props["java.awt.headless"] = headless ? "true" : "false"

  java = zb_find_java
  sample = zb_sample_approval_entries

  Dir.mktmpdir("zb-approval-dialog-") do |dir|
    req = File.join(dir, "request.json")
    resp = File.join(dir, "response.json")
    File.write(req, zb_sample_batch_request_v6_json(sample))

    cmd = [ java, *props.map{ |*a| "-D" + a.join('=') }, "-XstartOnFirstThread", "-cp", cp.join(":"), main_class, req, resp ]
    puts cmd.join(" ")
    system(*cmd)
    st = $?.exitstatus
    if st != 0
      warn "#{main_class} exited #{st} (cancel = 2)"
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

namespace :approval_dialog do
  desc "Run Swing-based mod approval dialog"
  task :swing do
    zb_run_approval_dialog(ZB_SWING_APPROVAL_MAIN, headless: false)
  end

  desc "Run Imgui-based mod approval dialog"
  task :imgui do
    game_root = File.expand_path("~/projects/zomboid/versions/unstable/java")
    cp = [
      File.join(game_root, "projectzomboid.jar")
    ]
    props = {
      "java.library.path" => File.join(game_root, "mac-aarch64")
    }
    zb_run_approval_dialog(ZB_IMGUI_APPROVAL_MAIN, headless: true, props:, cp:)
  end
end
