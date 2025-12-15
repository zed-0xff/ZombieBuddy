
desc 'install'
task :install => :chdir do
  jar = "ZombieBuddy.jar"
  src_dir = "build/libs"
  dst_dir = File.expand_path("~/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/")

  src = File.join(src_dir, jar)
  dst = File.join(dst_dir, jar)
  puts "[.] installing #{src} -> #{dst}"
  FileUtils.cp src, dst
end
