desc 'build'
task :build => ["42_13:build", "clean", "42_12:build"]

namespace "42_12" do
  desc 'build'
  task :build => :chdir do
    env = {
      "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"
    }
    cp_root = File.join(PROJECT_ROOT, "versions/42.12/java")
    cp = [cp_root, File.join(cp_root, "lwjgl-glfw.jar")].join(",")

    sh env, "gradle build --warning-mode all -PgameClasspath=#{cp}"
  end
end

namespace "42_13" do
  desc 'build'
  task :build => :chdir do
    env = {
      "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-24.jdk/Contents/Home"
    }
    cp_root = File.join(PROJECT_ROOT, "versions/42.13/java")
    cp = [File.join(cp_root, "projectzomboid.jar")].join(",")

    sh env, "gradle build --warning-mode all -PgameClasspath=#{cp}"
  end
end
