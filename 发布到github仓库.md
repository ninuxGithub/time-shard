

p配置  settings.xml
<server>
    <id>github</id>
    <username>ninuxGithub</username>
    <password>tokenxxx</password>
</server>


配置pom.xml
<distributionManagement>
    <repository>
    <id>github</id>
    <name>GitHub NinuxGithub Apache Maven Packages</name>
    <url>https://maven.pkg.github.com/ninuxgithub/time-shard</url>
    </repository>
</distributionManagement>