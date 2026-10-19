"""在仅监听本机的临时目录验收指定 Paper/Folia, 不访问生产服目录"""
import argparse
from contextlib import closing
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
BUILDS = {
    "paper": (205, "a0a58f532f08054ea92eb916d3edb6ed3c86429d77175317304f6e995cce9968"),
    "folia": (3, "0c4cc7dd82c40bd1effa8a9c4cacfad55d41b7dc38377900957c961c2952c022"),
}


class Server:
    def __init__(self, platform, run):
        self.platform = platform
        self.folder = run / platform
        self.folder.mkdir(parents=True, exist_ok=True)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        self.lines = []
        self.process = None
        self.log = None
        self.db = self.folder / "plugins/4V4DBind/database.db"
        self.checks = []
        self.runtime_versions = {}

    def prepare(self):
        build, checksum = BUILDS[self.platform]
        name = f"{self.platform}-26.3-{build}.jar"
        download = ROOT / "build/acceptance/downloads" / name
        download.parent.mkdir(parents=True, exist_ok=True)
        if not download.exists():
            url = f"https://fill-data.papermc.io/v1/objects/{checksum}/{name}"
            with urllib.request.urlopen(url, timeout=180) as response, download.open("wb") as output:
                shutil.copyfileobj(response, output)
        assert hashlib.sha256(download.read_bytes()).hexdigest() == checksum, "下载校验失败"
        self.jar = download
        plugins = self.folder / "plugins"
        plugins.mkdir(exist_ok=True)
        shutil.copy2(ROOT / "build/libs/4V4DBind-1.0.2.jar", plugins / "4V4DBind.jar")
        (self.folder / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        properties = {
            "server-ip": "127.0.0.1", "server-port": str(self.port), "online-mode": "false",
            "enforce-secure-profile": "false", "enable-rcon": "false", "enable-query": "false",
            "enable-status": "true", "max-players": "2", "view-distance": "2", "simulation-distance": "2",
            "spawn-protection": "0", "generate-structures": "false", "level-type": "minecraft:normal",
            "sync-chunk-writes": "true", "allow-flight": "true", "max-tick-time": "-1",
        }
        (self.folder / "server.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8")

    def start(self):
        self.lines = []
        self.log = (self.folder / f"acceptance-{int(time.time())}.log").open("w", encoding="utf-8")
        env = os.environ.copy()
        env.pop("JAVA_TOOL_OPTIONS", None)
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        java_options = ["-Djdk.net.unixdomain.tmpdir=C:\\Windows\\BayMcNonexistentSocketDir"] if os.name == "nt" else []
        self.process = subprocess.Popen([
            "java", "-Xms256M", "-Xmx1536M", "-XX:ActiveProcessorCount=4",
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-Dterminal.jline=false", "-Dterminal.ansi=false",
            "--enable-native-access=ALL-UNNAMED", *java_options, "-jar", str(self.jar), "--nogui",
        ], cwd=self.folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", creationflags=flags, env=env)

        def read():
            for line in self.process.stdout:
                self.lines.append(line)
                self.log.write(line)
                self.log.flush()
                if "4V4DBind" in line or "Done (" in line or "ERROR" in line:
                    print(f"[{self.platform}] {line.rstrip()}", flush=True)
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        self.wait(lambda: any("Done (" in line for line in self.lines)
                  and any("JDBC=" in line for line in self.lines), 240)
        for line in self.log_lines():
            version = re.search(r"JDBC=([^,\s]+), SQLite=([^,\s]+)", line)
            if version:
                self.runtime_versions = {"jdbc": version.group(1), "sqlite": version.group(2)}
        self.checks.append("startup")

    def wait(self, predicate, timeout=12):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            if self.process.poll() is not None:
                raise RuntimeError(f"{self.platform} 提前退出: {self.process.returncode}")
            time.sleep(0.1)
        raise TimeoutError(f"{self.platform} 等待验收结果超时")

    def sql(self, query, params=()):
        with closing(sqlite3.connect(self.db, timeout=5)) as connection:
            return connection.execute(query, params).fetchall()

    def log_lines(self):
        path = self.folder / "logs/latest.log"
        return path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []

    def command(self, command, expected):
        time.sleep(1.15)
        cursor = len(self.log_lines())
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
        self.wait(lambda: any(expected in line for line in self.log_lines()[cursor:]))
        return "\n".join(self.log_lines()[cursor:])

    def stop(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            self.process.stdin.write("stop\n")
            self.process.stdin.flush()
            try:
                self.process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
                raise RuntimeError("测试服未能正常停服")
        self.reader.join(timeout=5)
        self.log.close()
        if self.process.returncode != 0:
            raise RuntimeError(f"测试服退出码异常: {self.process.returncode}")
        self.checks.append("shutdown")

    def exercise(self):
        owner = str(uuid.UUID("00000000-0000-4000-8000-000000000001"))
        other = str(uuid.UUID("00000000-0000-4000-8000-000000000002"))
        assert self.db.is_file()
        self.checks.append("database-file-name")
        with closing(sqlite3.connect(self.db, timeout=5)) as connection, connection:
            connection.executemany("INSERT INTO players(uuid,name,name_key) VALUES(?,?,?)",
                                   [(owner, "TestOwner", "testowner"), (other, "TestOther", "testother")])
        self.command("bind", "4V4DBind 帮助")
        self.command("bind admin add TestOwner QQ号 12345678", "已绑定 QQ号")
        self.command("bind admin add TestOwner QQ号 87654321", "已绑定 QQ号")
        self.command("bind admin add TestOwner QQ号 12345678", "已经绑定过相同")
        self.command("bind admin add TestOther QQ号 12345678", "已绑定 QQ号")
        assert self.sql("SELECT COUNT(*) FROM bindings WHERE type_id='qq'")[0][0] == 3
        self.checks.extend(["multiple-values", "owner-deduplication", "cross-owner-duplicate"])
        self.command("bind admin add TestOwner 邮箱 Fixture@EXAMPLE.COM", "已绑定 邮箱")
        assert self.sql("SELECT values_json FROM bindings WHERE type_id='email'")[0][0].find("Fixture@example.com") >= 0
        self.command("bind admin add TestOwner 户籍 测试姓名 11010519491231002x", "已绑定 户籍")
        self.command("bind admin add TestOwner 户籍 其他姓名 11010519491231002X", "已经绑定过相同")
        self.command("bind admin add TestOwner 手机号 13800000000", "已绑定 手机号")
        self.command("bind admin add TestOwner 手机号 19900000000", "已绑定 手机号")
        self.command("bind admin add TestOwner 手机号 13800000000", "已经绑定过相同")
        self.command("bind admin add TestOther 手机号 13800000000", "已绑定 手机号")
        self.command("bind admin add TestOwner 手机号 12800000000", "输入内容无效")
        assert self.sql("SELECT COUNT(*) FROM bindings WHERE type_id='phone'")[0][0] == 3
        self.checks.extend(["phone-multiple-values", "phone-owner-deduplication", "phone-cross-owner-duplicate",
                            "phone-format-validation"])
        identity_id = self.sql("SELECT id FROM bindings WHERE type_id='identity'")[0][0]
        self.command(f"bind admin view TestOwner {identity_id}", "身份证号码")
        self.command(f"bind admin view TestOther {identity_id}", "记录不存在或无权访问")
        listing = self.command("bind admin list TestOwner", "第 1/1 页")
        assert "11010519491231002X" not in listing and "13800000000" not in listing and "****" in listing
        self.checks.extend(["email-normalization", "identity-pair", "identity-deduplication", "owner-scoped-view", "masked-list"])
        first_id = self.sql("SELECT MIN(id) FROM bindings WHERE owner_uuid=? AND type_id='qq'", (owner,))[0][0]
        self.command(f"bind admin remove TestOwner {first_id}", "已删除绑定记录")
        self.command(f"bind admin remove TestOwner {first_id}", "记录不存在或无权访问")
        self.command("bind admin add TestOwner QQ号 12345678", "已绑定 QQ号")
        assert self.sql("SELECT MAX(id) FROM bindings")[0][0] > identity_id
        self.command("bind reload", "配置已重载")
        self.checks.extend(["delete", "stable-increasing-id", "reload"])
        config = self.folder / "plugins/4V4DBind/config.yml"
        original = config.read_text(encoding="utf-8")
        config.write_text(original + "\nunknown-executable-key: ignored\n", encoding="utf-8")
        self.command("bind reload", "配置重载失败")
        self.command("bind admin list TestOwner", "第 1/1 页")
        config.write_text(original, encoding="utf-8")
        self.command("bind reload", "配置已重载")
        self.checks.append("failed-reload-retains-configuration")
        assert "  qq: 10" in original and "  phone: 10" in original
        lowered = original.replace("  qq: 10", "  qq: 1", 1).replace("  phone: 10", "  phone: 1", 1)
        config.write_text(lowered, encoding="utf-8")
        self.command("bind reload", "配置已重载")
        self.command("bind admin add TestOwner QQ号 99999999", "已达到绑定数量上限 1")
        self.command("bind admin add TestOwner 手机号 13900000000", "已达到绑定数量上限 1")
        assert self.sql("SELECT COUNT(*) FROM bindings WHERE owner_uuid=? AND type_id='qq'", (owner,))[0][0] == 2
        assert self.sql("SELECT COUNT(*) FROM bindings WHERE owner_uuid=? AND type_id='phone'", (owner,))[0][0] == 2
        self.command("bind admin list TestOwner QQ号", "第 1/1 页")
        self.checks.append("lowered-limits-retain-existing-data")
        config.write_text(lowered.replace("  qq: 1", "  qq: -1", 1), encoding="utf-8")
        self.command("bind reload", "配置已重载")
        self.command("bind admin add TestOwner QQ号 99999999", "已绑定 QQ号")
        self.checks.append("unlimited-binding-limit")
        assert "binding-limits:\n" in original
        config.write_text(original.replace("binding-limits:\n", "binding-limits:\n  note: 10\n", 1), encoding="utf-8")
        self.command("bind reload", "配置重载失败")
        self.command("bind admin add TestOwner 备注 fixture", "绑定类型不存在或当前不可用")
        self.checks.append("unknown-limit-type-rejected")
        config.write_text(original, encoding="utf-8")
        self.command("bind reload", "配置已重载")
        before = self.sql("SELECT id,values_json FROM bindings ORDER BY id")
        self.stop()
        self.start()
        assert self.sql("SELECT id,values_json FROM bindings ORDER BY id") == before
        assert self.db.is_file()
        self.command("bind admin list TestOwner", "第 1/1 页")
        self.checks.append("restart-persistence")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=["paper", "folia", "all"], default="all")
    parser.add_argument("--accept-eula", action="store_true", help="确认接受Minecraft EULA后启动临时测试服")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("启动前需要接受 Minecraft EULA: https://www.minecraft.net/eula")
    run = ROOT / "build/acceptance" / time.strftime("run-%Y%m%d-%H%M%S")
    report = {"artifact": "4V4DBind-1.0.2.jar",
              "artifact_sha256": hashlib.sha256((ROOT / "build/libs/4V4DBind-1.0.2.jar").read_bytes()).hexdigest(),
              "player_client_scenarios": "NOT_RUN: available client protocol library does not support 26.3", "platforms": {}}
    platforms = list(BUILDS) if args.platform == "all" else [args.platform]
    for platform in platforms:
        server = Server(platform, run)
        result = {"checks": server.checks}
        report["platforms"][platform] = result
        try:
            server.prepare()
            server.start()
            server.exercise()
            result["result"] = "PASS"
            result["runtime_versions"] = server.runtime_versions
        except Exception as error:
            result["result"] = "FAIL"
            result["error"] = str(error)
            print(f"[{platform}] FAIL: {error}", flush=True)
        finally:
            try:
                server.stop()
            except Exception as error:
                result["result"] = "FAIL"
                result["shutdown_error"] = str(error)
            run.mkdir(parents=True, exist_ok=True)
            (run / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[{platform}] {result['result']}: {server.checks}", flush=True)
    print(f"REPORT={run / 'report.json'}", flush=True)
    if any(result["result"] != "PASS" for result in report["platforms"].values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
