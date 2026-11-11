"""直接校验 SVG 的端点、正交通道、间距和图外文字。"""

import math
import re
from itertools import pairwise

SVG = "{http://www.w3.org/2000/svg}"


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


def validate(svg):
    if any(item.tag == SVG + "foreignObject" for item in svg.iter()):
        raise ValueError("SVG 应使用原生 text 标签")
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
