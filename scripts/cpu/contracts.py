#!/usr/bin/env python3
"""CPU 发布边界使用的无状态黑盒合同。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
from typing import Any


class ContractError(ValueError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ContractError(f"JSON 根必须是对象: {path}")
    return value


def validate_port_contract(document: dict[str, Any]) -> list[dict[str, Any]]:
    if document.get("schema_version") != 1 or document.get("module") != "core_top":
        raise ContractError("core_top 合同版本或模块名错误")
    parameters = document.get("parameters")
    if parameters != [{"name": "TLBNUM", "default": 32}]:
        raise ContractError("只支持 TLBNUM=32")
    ports = document.get("ports")
    if not isinstance(ports, list) or len(ports) != 49:
        raise ContractError("core_top 必须有 49 个端口")
    names: set[str] = set()
    normalized: list[dict[str, Any]] = []
    for index, port in enumerate(ports):
        if not isinstance(port, dict) or set(port) != {"name", "direction", "width"}:
            raise ContractError(f"ports[{index}] schema 错误")
        name, direction, width = port["name"], port["direction"], port["width"]
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise ContractError(f"ports[{index}] 名称错误")
        if name in names or direction not in {"input", "output"} or not isinstance(width, int) or width < 1:
            raise ContractError(f"ports[{index}] 方向、宽度或重复错误")
        names.add(name)
        normalized.append({"name": name, "direction": direction, "width": width})
    if sum(p["direction"] == "input" for p in normalized) != 17:
        raise ContractError("core_top 输入端口数错误")
    if sum(p["direction"] == "output" for p in normalized) != 32:
        raise ContractError("core_top 输出端口数错误")
    return normalized


def _mask_comments(text: str) -> str:
    return re.sub(
        r"//[^\r\n]*|/\*.*?\*/",
        lambda match: "".join(c if c in "\r\n" else " " for c in match.group(0)),
        text,
        flags=re.DOTALL,
    )


def parse_core_top_header(text: str) -> tuple[list[dict[str, Any]], int]:
    masked = _mask_comments(text)
    match = re.search(r"(?ms)^\s*module\s+core_top\b.*?^\s*\);", masked)
    if match is None or len(re.findall(r"(?m)^\s*module\s+core_top\b", masked)) != 1:
        raise ContractError("core_top module/header 不唯一")
    header = masked[match.start() : match.end()]
    parameter = re.search(r"\bparameter\s+(?:integer\s+)?TLBNUM\s*=\s*(\d+)", header)
    if parameter is None:
        raise ContractError("缺少 TLBNUM 参数")
    ports: list[dict[str, Any]] = []
    declaration = re.compile(
        r"^\s*(input|output)\s+(?:(?:wire|reg)\s+)?"
        r"(?:\[\s*(\d+)\s*:\s*(\d+)\s*\]\s+)?"
        r"([A-Za-z_][A-Za-z0-9_]*)\s*,?\s*$"
    )
    for line in header.splitlines():
        if not re.match(r"^\s*(input|output)\b", line):
            continue
        item = declaration.fullmatch(line)
        if item is None:
            raise ContractError(f"无法解析端口声明: {line.strip()}")
        direction, msb, lsb, name = item.groups()
        ports.append({"name": name, "direction": direction, "width": 1 if msb is None else abs(int(msb) - int(lsb)) + 1})
    return ports, int(parameter.group(1))


def validate_rtl(text: str, contract: list[dict[str, Any]]) -> dict[str, Any]:
    if "legacy_inorder_core" in text:
        raise ContractError("发布 RTL 仍包含已删除的旧后端")
    ports, tlbnum = parse_core_top_header(text)
    if tlbnum != 32 or ports != contract:
        raise ContractError("发布 RTL 与 core_top 公开合同不一致")
    return {"module": "core_top", "ports": len(ports), "tlbnum": tlbnum, "rtl_sha256": hashlib.sha256(text.encode()).hexdigest()}


def validate_generation_manifest(document: dict[str, Any]) -> None:
    required = {"schema_version", "source_tree_sha256", "raw_rtl_sha256", "published_rtl_sha256", "toolchain"}
    if not required.issubset(document):
        raise ContractError(f"生成清单缺少字段: {sorted(required - set(document))}")
    for key in ("source_tree_sha256", "raw_rtl_sha256", "published_rtl_sha256"):
        if re.fullmatch(r"[0-9a-f]{64}", str(document[key])) is None:
            raise ContractError(f"生成清单哈希错误: {key}")
    if not isinstance(document["toolchain"], dict):
        raise ContractError("生成清单 toolchain 必须是对象")


def validate_sim_result(document: dict[str, Any]) -> None:
    required = {
        "schema_version", "status", "workload", "seed", "cycles",
        "model_sha256", "model_key", "software_key", "end_reason",
    }
    if not required.issubset(document):
        raise ContractError(f"仿真结果缺少字段: {sorted(required - set(document))}")
    if document["status"] not in {"pass", "fail", "timeout", "error"}:
        raise ContractError("仿真 status 非法")
    if not isinstance(document["seed"], int) or document["seed"] < 0:
        raise ContractError("仿真 seed 必须是非负整数")
    if not isinstance(document["cycles"], int) or document["cycles"] < 0:
        raise ContractError("仿真 cycles 必须是非负整数")
    if re.fullmatch(r"[0-9a-f]{64}", str(document["model_sha256"])) is None:
        raise ContractError("仿真模型哈希错误")
    if document["schema_version"] != 2:
        raise ContractError("仿真结果 schema 版本错误")
    for key in ("model_key", "software_key"):
        if re.fullmatch(r"[0-9a-f]{64}", str(document[key])) is None:
            raise ContractError(f"仿真缓存身份错误: {key}")


def classify_failure(message: str) -> str:
    lower = message.lower()
    if any(token in lower for token in ("missing", "not found", "配置", "config")):
        return "config"
    if any(token in lower for token in ("json", "manifest", "artifact", "hash")):
        return "artifact"
    if any(token in lower for token in ("harness", "parser", "fixture", "schema")):
        return "harness"
    return "dut"


def validate_candidate_manifest(document: dict[str, Any]) -> None:
    required = {"schema_version", "cpu_source_commit", "rtl_sha256", "software_sha256", "clock", "results"}
    if not required.issubset(document):
        raise ContractError(f"候选清单缺少字段: {sorted(required - set(document))}")
    if not isinstance(document["results"], list):
        raise ContractError("候选 results 必须是数组")
    for key in ("rtl_sha256", "software_sha256"):
        if re.fullmatch(r"[0-9a-f]{64}", str(document[key])) is None:
            raise ContractError(f"候选清单哈希错误: {key}")
