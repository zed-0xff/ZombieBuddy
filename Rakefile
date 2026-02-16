PROJECT_ROOT = File.expand_path("~/projects/zomboid")

# only for install/launch tasks
GAME_ROOT    = File.expand_path("~/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/")

Dir["lib/tasks/*.rake"].each { |r| load r }
Dir["lib/tasks/*.rake.local"].each { |r| load r }

task :default => [:build, :install]

task :chdir do
  Dir.chdir("java")
end

desc 'clean the project'
task :clean => :chdir do
  sh "gradle clean"
end

desc "run the game"
task :run, :verbosity, :exit_after_game_init do |t, args|
  cmd_args = ['experimental']
  cmd_args << "verbosity=#{args.verbosity}" if args.verbosity
  cmd_args << "exit_after_game_init" if args.exit_after_game_init
  cmd_args_str = cmd_args.empty? ? "" : "=" + cmd_args.join(",")

  sh File.join(GAME_ROOT, "MacOS/JavaAppLauncher"), "-javaagent:ZombieBuddy.jar#{cmd_args_str}", "--"
end
