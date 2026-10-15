# /// script
# requires-python = ">=3.11"
# dependencies = ["diagrams==0.25.1", "cairosvg>=2.7", "pillow>=10"]
# ///
"""Draws architecture.png, the Embedding Zoo on the Atlas Agent Engine.

    uv run docs/architecture.py        (needs Graphviz's `dot` on the PATH)

Java, Python, MongoDB and the Bash icon ship with the `diagrams` package. The other logos are
downloaded once into docs/.logos: full-colour ones from the Iconify `logos` set (the caller is a Noto
emoji from Iconify too), and NASA, Netflix and ONNX from Simple Icons (CC0, pinned), tinted in their
brand colour. The logos are trademarks of their owners.
"""

import json
import re
import urllib.request
from pathlib import Path

import cairosvg
from diagrams import Cluster, Diagram, Edge, Node
from diagrams.custom import Custom
from diagrams.onprem.database import Mongodb
from diagrams.programming.language import Bash, Java, Python

HERE = Path(__file__).parent
ICONIFY = "https://api.iconify.design/{icon}.svg"
SIMPLE_ICONS = "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/{name}.svg"
SIMPLE_ICONS_COLOURS = {"nasa": "E03C31", "netflix": "E50914", "onnx": "005CED"}
# Glyphs that fill their whole square and would otherwise touch the caption under them.
PADDED = {"onnx"}


def download(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "embedding-zoo-architecture/1.0"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode()


def padded(svg: str, margin: int = 40, size: int = 256) -> str:
    """The logo inside a larger canvas, leaving a margin around the glyph."""
    inner = re.sub(r"<\?xml[^>]*\?>", "", svg).strip()
    opening = re.match(r"<svg[^>]*>", inner).group(0)
    bare = re.sub(r'\s(?:width|height|x|y)="[^"]*"', "", opening)
    bare = bare.replace("<svg", f'<svg x="{margin}" y="{margin}" width="{size}" height="{size}"', 1)
    inner = bare + inner[len(opening):]
    total = size + 2 * margin
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="{total}" viewBox="0 0 {total} {total}">{inner}</svg>'


def place_label(layout: dict, tail, head, text: str) -> None:
    """Draws a grey label centred on a vertical arrow, using the node positions Graphviz computed.

    Graphviz gives an edge label no way to sit on a straight line, so it is added to the PNG.
    """
    from PIL import Image, ImageDraw, ImageFont

    nodes = {n["name"]: n for n in layout["objects"] if "pos" in n}
    x_tail, y_tail = (float(v) for v in nodes[tail._id]["pos"].split(","))
    x_head, y_head = (float(v) for v in nodes[head._id]["pos"].split(","))
    half_tail = float(nodes[tail._id]["height"]) * 36
    half_head = float(nodes[head._id]["height"]) * 36
    # Graphviz y grows upwards; the arrow runs from the tail's bottom to the head's top.
    y_mid = ((y_tail - half_tail) + (y_head + half_head)) / 2
    _, _, width, height = (float(v) for v in layout["bb"].split(","))
    pad = float(GRAPH["pad"]) * 72
    path = HERE / "architecture.png"
    image = Image.open(path).convert("RGB")
    scale = image.width / (width + 2 * pad)
    cx, cy = (x_tail + pad) * scale, (height - y_mid + pad) * scale
    font = ImageFont.truetype("DejaVuSansMono.ttf", round(float(EDGE["fontsize"]) * scale * 1.0))
    draw = ImageDraw.Draw(image)
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    box_w, box_h = right - left + 14 * scale, bottom - top + 12 * scale
    draw.rectangle((cx - box_w / 2, cy - box_h / 2, cx + box_w / 2, cy + box_h / 2), fill="#ECEFF1")
    draw.text((cx - (right + left) / 2, cy - (bottom + top) / 2), text, font=font, fill="#263238")
    image.save(path)


def logo(name: str) -> str:
    """A PNG of the named logo, rendered once and cached. ``set/name`` picks another Iconify set."""
    png = HERE / ".logos" / f"{name.replace('/', '-')}.png"
    if not png.exists():
        png.parent.mkdir(exist_ok=True)
        if name in SIMPLE_ICONS_COLOURS:
            svg = download(SIMPLE_ICONS.format(name=name))
            svg = re.sub(r"<svg ", f'<svg fill="#{SIMPLE_ICONS_COLOURS[name]}" ', svg, count=1)
        else:
            svg = download(ICONIFY.format(icon=name if "/" in name else f"logos/{name}"))
        if name in PADDED:
            svg = padded(svg)
        cairosvg.svg2png(bytestring=svg.encode(), write_to=str(png), output_width=256, output_height=256)
    return str(png)


# Curved edges: Graphviz cannot place edge labels on the default orthogonal ones.
GRAPH = {"pad": "0.4", "nodesep": "1.3", "ranksep": "1.5", "splines": "spline", "compound": "true"}
NODE = {"fontsize": "20"}
EDGE = {"fontsize": "20"}


_node_init = Node.__init__

ICON_HEIGHT = 1.4  # inches the library reserves for an icon
GAP = 0.2  # between the icon and its caption
LINE = 0.34  # one caption line at 20 pt


def _sized_node_init(self, label: str = "", **attributes) -> None:
    """Sizes a node to its icon plus its caption, with the icon at the top.

    The library grows a node by a fixed amount per caption line and centres the icon in it, which
    leaves blank space above the icon in boxes holding several-line captions.
    """
    lines = label.count("\n") + 1 if label else 0
    attributes.setdefault("height", f"{ICON_HEIGHT + GAP + LINE * lines:.2f}")
    attributes.setdefault("imagepos", "tc")
    _node_init(self, label, **attributes)


Node.__init__ = _sized_node_init

# The library gives every edge its own defaults, which beat the diagram-wide settings.
Edge._default_edge_attrs = {
    **Edge._default_edge_attrs,
    "fontsize": "20",
    "color": "#546E7A",
    "fontcolor": "#263238",
    "penwidth": "1.8",
    "arrowsize": "1.2",
    "labelfloat": "true",
}


def tag(*lines: str) -> str:
    """An arrow label drawn as a small grey box, so it stays readable on top of the line."""
    body = "<BR/>".join(lines)
    return (
        '<<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="5" BGCOLOR="#ECEFF1">'
        f"<TR><TD>{body}</TD></TR></TABLE>>"
    )


def code(text: str) -> str:
    """Monospace markup for an endpoint, a stage or an index inside an HTML-like label."""
    return f'<FONT FACE="DejaVu Sans Mono">{text}</FONT>'


def box(label: str, key: str | None = None, title_padding: int = 12, emoji: str | None = None, **attributes: str) -> Cluster:
    """A cluster with room between its border and the labels inside, and around its own title.

    Graphviz has no padding for plain cluster titles, but an HTML-like label can carry cell padding.
    """
    title = label.replace("\n", "<BR/>")
    icon = (
        f'<TD WIDTH="90" HEIGHT="90" FIXEDSIZE="TRUE"><IMG SRC="{logo("noto/" + emoji)}" SCALE="TRUE"/></TD>'
        if emoji
        else ""
    )
    html = (
        f'<<TABLE BORDER="0" CELLBORDER="0" CELLPADDING="{title_padding}"><TR>{icon}<TD>{title}</TD></TR></TABLE>>'
    )
    cluster = Cluster(html, graph_attr={"margin": "28", "fontsize": "24", **attributes})
    if key:
        # A plain id, so edges can start at the box itself (ltail) instead of at a node inside it.
        cluster.name = cluster.dot.name = f"cluster_{key}"
    return cluster


with Diagram(
    "",
    filename=str(HERE / "architecture"),
    outformat="png",
    show=False,
    direction="LR",
    graph_attr=GRAPH,
    node_attr=NODE,
    edge_attr=EDGE,
) as diagram:
    caller = Custom("Caller", logo("noto/technologist"))

    with box("Your machine", emoji="laptop"):
        # The packaging path comes first, so Graphviz stacks it above the ingest path: the source
        # archive then goes up to the platform and the upserts go right to Atlas without crossing.
        with box("Package", margin="36"):
            minilm = Custom("MiniLM\n(Hugging Face)", logo("hugging-face-icon"))
            maven = Custom("mvn package", logo("maven"))
            package = Bash("package script\n+ CLI")
        with box("Ingest"):
            with box("Public data", key="public_data", title_padding=4, margin="26"):
                nasa = Custom("NASA IMS\nbearings", logo("nasa"))
                loghub = Custom("Loghub\nHDFS logs", logo("github-icon"))
                netflix = Custom("Netflix\npostmortems", logo("netflix"))
            ingest = Java("Embed and\nupsert")

    with box("MongoDB Atlas Agent Engine (managed)", emoji="robot"):
        engine = Mongodb("Orchestration\nEngine")
        with box("Agent sandbox\n(microVM)", margin="50"):
            shim = Python("Python shim")
            runner = Java("Java runner")
        with box("Tool sandbox\n(microVM)"):
            tools = Java("Java tools")
            onnx = Custom("ONNX Runtime\nMiniLM int8", logo("onnx"))

    openai = Custom("OpenAI\nchat model", logo("openai-icon"))

    with box(f"AWS / {code('us-east-1')}", labelloc="b", emoji="cloud"):
        with box("MongoDB Atlas Flex cluster", labelloc="b"):
            atlas = Mongodb("Vector Search\nembeddings, metadata,\nprovenance")
        aws = Custom("", logo("aws"))


    # Invisible links lay the sources out in a row; one arrow leaves the box for ingest.
    nasa >> Edge(style="invis") >> loghub >> Edge(style="invis") >> netflix
    netflix >> Edge(ltail="cluster_public_data") >> ingest
    minilm >> Edge(label=tag("model", "files")) >> ingest
    ingest >> Edge(label=tag("upsert", "records"), constraint="false", tailport="e", headport="w") >> atlas
    maven >> Edge(label=tag("jars")) >> package
    minilm >> Edge(label=tag("int8 file"), style="dotted") >> package
    package >> Edge(label=tag("source archive", "(under 50 MiB)"), style="dotted", headport="s") >> engine

    caller >> Edge(label=tag("invoke")) >> engine
    engine >> Edge(label=tag(code("/execute"), "mTLS")) >> shim
    shim >> Edge(label=tag("execs", "the JVM")) >> runner
    runner >> Edge(label=tag("model and", "tool calls"), style="dashed") >> engine
    engine >> Edge(label=tag("approved", "calls")) >> tools
    tools >> Edge(label=tag("embeds", "queries"), minlen="0") >> onnx
    # Zero rank distance stacks Atlas directly under the tool server, below its sandbox.
    # Straight, unlabelled: a label would make Graphviz route the line around it. The text is drawn
    # onto the finished image, see place_label below.
    tools >> Edge(minlen="0", tailport="s") >> atlas
    tools >> Edge(label=tag("chat", "completions"), minlen="2", tailport="e") >> openai

    layout = json.loads(diagram.dot.pipe(format="json0"))

place_label(layout, tools, atlas, "$vectorSearch")
