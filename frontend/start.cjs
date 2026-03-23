process.chdir(__dirname);
require("child_process").spawn("node", ["node_modules/vite/bin/vite.js"], { stdio: "inherit" });
