desc 'build'
task :build => ["build:unstable", "clean", "build:42_12"]

desc 'clean the project'
task :clean do
  Dir.chdir("java") do
    sh "gradle clean"
  end
end

namespace :build do
  # runs first
  desc 'build'
  task :unstable do
    env = {
      "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-24.jdk/Contents/Home"
    }
    cp_root = File.join(PROJECT_ROOT, "versions/unstable/java")
    cp = [File.join(cp_root, "projectzomboid.jar")].join(",")
 
    Dir.chdir("java") do
      sh env, "gradle build --warning-mode all -PgameClasspath=#{cp}"
    end
  end

  # runs last, result is the final build
  desc 'build'
  task "42_12" do
    env = {
      "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"
    }
    cp_root = File.join(PROJECT_ROOT, "versions/42.12/java")
    cp = [
      cp_root,
      File.join(cp_root, "lwjgl.jar"),
      File.join(cp_root, "lwjgl-glfw.jar"),
      File.join(cp_root, "lwjgl-opengl.jar"),
      File.join(cp_root, "imgui-binding-1.86.11-8-g3e33dde.jar"),
    ].join(",")

    Dir.chdir("java") do
      sh env, "gradle build --warning-mode all -PgameClasspath=#{cp}"
    end
  end
end
