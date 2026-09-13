# 진짜 boot JAR 과 같은 구조로 가짜를 만든다.
# 항목 시각을 1980-02-01 로 눌러 두는 것까지 bootJar 과 같다
import sys, zipfile
path, artifact, body, built_at = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
    def add(name, data):
        info = zipfile.ZipInfo(name, date_time=(1980, 2, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data)
    add('META-INF/MANIFEST.MF',
        'Manifest-Version: 1.0\n'
        'Main-Class: org.springframework.boot.loader.launch.JarLauncher\n')
    add('META-INF/build-info.properties',
        f'build.artifact={artifact}\nbuild.group=com.gustler\nbuild.name={artifact}\n'
        f'build.time={built_at}\nbuild.version=0.0.1-SNAPSHOT\n')
    add('BOOT-INF/classes/application.yml', body)
    add('BOOT-INF/classes/com/gustler/Main.class', body * 40)
