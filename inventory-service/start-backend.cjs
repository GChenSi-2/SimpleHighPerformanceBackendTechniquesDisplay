const { execSync } = require("child_process");
const path = require("path");

process.chdir(__dirname);
process.env.JAVA_HOME = "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.18.8-hotspot";
process.env.PATH = process.env.JAVA_HOME + "\\bin;C:\\tools\\apache-maven-3.9.14\\bin;" + process.env.PATH;

try {
  execSync("mvn spring-boot:run", { stdio: "inherit" });
} catch (e) {
  process.exit(e.status || 1);
}
