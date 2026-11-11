"""生成 Mermaid 正交端口走线并检查端点；仅调用本机 Mermaid CLI，不访问业务服务。"""

import argparse
import hashlib
import json
import math
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from itertools import pairwise
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SVG = "{http://www.w3.org/2000/svg}"
DIAGRAMS = ROOT / "docs/diagrams"


def number(value):
    return f"{value:.3f}".rstrip("0").rstrip(".")


def config_of(source):
    first, rest = source.split("\n", 1)
    return json.loads(first[len("%%{init: ") : -len("}%%")]), rest


def with_config(config, rest):
    return "%%{init: " + json.dumps(config, ensure_ascii=False) + "}%%\n" + rest


def nodes_of(svg):
    nodes = {}
    for group in svg.iter(SVG + "g"):
        if not group.get("class", "").startswith("node "):
            continue
        transform = re.fullmatch(
            r"translate\(([-\d.]+),\s*([-\d.]+)\)", group.get("transform", "")
        )
        rect = group.find(SVG + "rect")
        if transform is None or rect is None:
            continue
        x, y = map(float, transform.groups())
        left, top = x + float(rect.get("x")), y + float(rect.get("y"))
        nodes[group.get("id").removeprefix("my-svg-")] = (
            left,
            top,
            left + float(rect.get("width")),
            top + float(rect.get("height")),
        )
    return nodes


def edge_nodes(edge):
    return tuple(edge.get("data-id").split("-")[-2:])


def points_of(path):
    return [
        tuple(map(float, match))
        for match in re.findall(r"[ML]\s*(-?[\d.]+)[,\s]+(-?[\d.]+)", path)
    ]


def routes_of(svg):
    """同排保留水平双通道；跨排按独立端口生成 V-H-V，首尾均为矩形法线。"""
    nodes = nodes_of(svg)
    edges = {
        edge_nodes(edge): edge
        for edge in svg.iter(SVG + "path")
        if edge.get("data-edge")
    }
    ports = {}
    for node, rect in nodes.items():
        incident = []
        center = (rect[0] + rect[2]) / 2
        for (a, b), edge in edges.items():
            if node not in (a, b) or "edge-pattern-dotted" not in edge.get("class", ""):
                continue
            peer = b if node == a else a
            peer_rect = nodes[peer]
            peer_x = (peer_rect[0] + peer_rect[2]) / 2
            incoming_second = peer_x < center
            if abs(peer_x - center) < 0.001:
                incoming_second = rect[1] < peer_rect[1]
            order = int(incoming_second) if node == b else int(not incoming_second)
            incident.append((peer_x, order, a, b))
        for index, (_, _, a, b) in enumerate(sorted(incident)):
            x = center + (index - (len(incident) - 1) / 2) * 32
            if not rect[0] + 12 < x < rect[2] - 12:
                raise ValueError(f"{node}: 端口数量过多，需增加卡片宽度")
            ports[node, a, b] = x
    result = {}
    for (source, target), edge in edges.items():
        start_rect, end_rect = nodes[source], nodes[target]
        if "edge-pattern-dotted" not in edge.get("class", ""):
            direction = 1 if start_rect[0] < end_rect[0] else -1
            sx = start_rect[2] if direction == 1 else start_rect[0]
            tx = end_rect[0] if direction == 1 else end_rect[2]
            sy, ty = (
                (start_rect[1] + start_rect[3]) / 2,
                (end_rect[1] + end_rect[3]) / 2,
            )
            middle = (sx + tx) / 2
            points = [(sx, sy), (middle, sy), (middle, ty), (tx - 4 * direction, ty)]
            result[source, target] = "M" + "L".join(
                f"{number(x)},{number(y)}" for x, y in points
            )
            continue
        if start_rect[1] > end_rect[1]:
            continue
        reverse = (target, source) in edges
        sx, tx = ports[source, source, target], ports[target, source, target]
        sy, ty = start_rect[3], end_rect[1]
        middle = (sy + ty) / 2
        forward = [(sx, sy), (sx, middle - 14), (tx, middle - 14), (tx, ty - 4)]
        routes = [(source, target, forward)]
        if reverse:
            rx, ry = ports[target, target, source], ports[source, target, source]
            backward = [
                (rx, ty),
                (rx, middle + 14),
                (ry, middle + 14),
                (ry, sy + 4),
            ]
            routes.append((target, source, backward))
        for a, b, points in routes:
            d = "M" + "L".join(f"{number(x)},{number(y)}" for x, y in points)
            result[a, b] = d
    return result


def validate(svg):
    if any(item.tag == SVG + "foreignObject" for item in svg.iter()):
        raise ValueError("SVG 应使用原生 text 标签，请设置 htmlLabels: false")
    nodes = nodes_of(svg)
    count = 0
    paths = {}
    for edge in svg.iter(SVG + "path"):
        if not edge.get("data-edge"):
            continue
        source, target = edge_nodes(edge)
        raw = points_of(edge.get("d"))
        points = [
            p for i, p in enumerate(raw) if i == 0 or math.dist(p, raw[i - 1]) > 0.01
        ]
        paths[source, target] = list(pairwise(points))
        for a, b in pairwise(points):
            if abs(a[0] - b[0]) > 0.01 and abs(a[1] - b[1]) > 0.01:
                raise ValueError(f"{source} → {target}: 非正交线段")
        for node, a, b, endpoint in [
            (source, points[0], points[1], points[0]),
            (target, points[-2], points[-1], points[-1]),
        ]:
            left, top, right, bottom = nodes[node]
            x, y = endpoint
            if abs(a[0] - b[0]) < 0.01:
                valid = (
                    min(abs(y - top), abs(y - bottom)) < 5 and left + 8 < x < right - 8
                )
            else:
                valid = (
                    min(abs(x - left), abs(x - right)) < 5 and top + 8 < y < bottom - 8
                )
            if not valid:
                raise ValueError(f"{source} → {target}: {node} 端点未垂直于平直边")
        count += 1
    if any(item.get("class") == "edgeLabel" for item in svg.iter(SVG + "g")):
        raise ValueError("通信文字应放在图外，不应创建连线标签")
    for (source, target), segments in paths.items():
        if source >= target or (target, source) not in paths:
            continue
        gap = min(
            math.hypot(
                max(
                    min(a[0], b[0]) - max(c[0], d[0]),
                    min(c[0], d[0]) - max(a[0], b[0]),
                    0,
                ),
                max(
                    min(a[1], b[1]) - max(c[1], d[1]),
                    min(c[1], d[1]) - max(a[1], b[1]),
                    0,
                ),
            )
            for a, b in segments
            for c, d in paths[target, source]
        )
        if gap < 20:
            raise ValueError(f"{source} ⇄ {target}: 通道间距不足 20 SVG 单位")
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--cli", default="mmdc", help="mmdc 可执行文件或 Mermaid CLI JS 路径"
    )
    parser.add_argument("--puppeteer-config", type=Path)
    parser.add_argument("--output", type=Path, default=DIAGRAMS)
    parser.add_argument("--preview", type=Path, help="同时导出 PNG 检查图到指定目录")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = ["node", args.cli] if args.cli.endswith(".js") else [args.cli]
    if args.puppeteer_config:
        command += ["-p", str(args.puppeteer_config.resolve())]

    def render(source, output, directory):
        input_file = directory / "input.mmd"
        input_file.write_text(source)
        subprocess.run(command + ["-i", str(input_file), "-o", str(output)], check=True)

    seen = set()
    total = 0
    pending = []
    with tempfile.TemporaryDirectory(prefix="polyglot-diagrams-") as tmp:
        directory = Path(tmp)
        for document in sorted(DIAGRAMS.glob("*.mmd")):
            source = document.read_text()
            config, rest = config_of(source)
            identity = re.sub(r"\s+", " ", rest).strip()
            if identity in seen:
                raise ValueError(f"重复 Mermaid 图：{document}")
            seen.add(identity)
            base = directory / "base.svg"
            render(with_config(config, rest), base, directory)
            svg = ET.parse(base).getroot()
            routes = routes_of(svg)
            for edge in svg.iter(SVG + "path"):
                if edge.get("data-edge") and edge_nodes(edge) in routes:
                    edge.set("d", routes[edge_nodes(edge)])
            count = validate(svg)
            ET.SubElement(
                svg, SVG + "metadata", id="polyglot-diagram-source"
            ).text = hashlib.sha256(source.encode()).hexdigest()
            # Bake paths into SVG attributes, without depending on CSS d support.
            ET.register_namespace("", "http://www.w3.org/2000/svg")
            ET.register_namespace("xhtml", "http://www.w3.org/1999/xhtml")
            ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")
            pending.append(
                (
                    args.output / document.with_suffix(".svg").name,
                    ET.tostring(svg, encoding="utf-8"),
                )
            )
            if args.preview:
                args.preview.mkdir(parents=True, exist_ok=True)
                # Temporary CSS only for CLI PNG previews; shipped SVG has native paths.
                config["themeCSS"] += " ".join(
                    f'path[id$="-{a}-{b}"] {{ d: path("{d}"); }}'
                    for (a, b), d in routes.items()
                )
                render(
                    with_config(config, rest),
                    (args.preview / document.with_suffix(".png").name).resolve(),
                    directory,
                )
            print(f"{document.stem}: {count} 条连线首尾垂直，文字位于图外", flush=True)
            total += count
    for output, data in pending:
        output.write_bytes(data)
    print(f"已渲染 {len(seen)} 张独立图，共 {total} 条连线。")


if __name__ == "__main__":
    main()
