PROJECT_ROOT = File.expand_path("~/projects/zomboid")

# only for install/launch tasks
GAME_ROOT    = File.expand_path("~/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/")

Dir["lib/tasks/*.rake"].each { |r| load r }
Dir["lib/tasks/*.rake.local"].each { |r| load r }

task :default => [:build, :install]

task :chdir do
  Dir.chdir("java")
end

def run_game(zb_args = {}, pz_args = [])
  ENV['ZB_ARGS'].to_s.split(",").each do |arg|
    k, v = arg.split("=", 2)
    zb_args[k] = v
  end
  ENV['PZ_ARGS'].to_s.split.each do |arg|
    pz_args << arg
  end
  zb_args[:experimental] = true
  pargs = zb_args.map { |k, v| "-Dzb.#{k}=#{v}" }
  sh File.join(GAME_ROOT, "MacOS/JavaAppLauncher"), "-javaagent:ZombieBuddy.jar", *pargs, "--", *pz_args
end

desc "run the game"
task :run, :verbosity, :exit_after_game_init do |t, args|
  zb_args = {}
  zb_args[:exit_after_game_init] = true if args.exit_after_game_init
  zb_args[:verbosity] = args.verbosity if args.verbosity
  run_game(zb_args)
end

namespace :run do
  %w'console imgui swing tinyfd'.each do |frontend|
    desc "run the game with #{frontend} frontend"
    task(frontend) do |t, args|
      run_game({frontend: })
    end
  end
end

desc "show steam url"
task :url do
  puts "https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853"
end
