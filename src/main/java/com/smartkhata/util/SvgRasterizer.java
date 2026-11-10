package com.smartkhata.util;

import javafx.scene.image.Image;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java lightweight SVG rasterizer converting clean vector SVG product illustrations
 * into high-DPI JavaFX {@link Image} instances on any thread without external runtime dependencies.
 */
public final class SvgRasterizer {

    private static final int DEFAULT_RENDER_SIZE = 192;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?");
    private static final Pattern TRANSFORM_FN = Pattern.compile("(translate|scale|rotate|matrix)\\s*\\(([^)]*)\\)");

    private record GradientDef(
            String type,
            double x1, double y1, double x2, double y2,
            double cx, double cy, double r,
            float[] fractions,
            Color[] colors
    ) {}

    private record RenderStyle(
            String fill,
            String stroke,
            double strokeWidth,
            double opacity,
            double fillOpacity,
            double strokeOpacity,
            String strokeLinecap,
            String strokeLinejoin,
            String strokeDasharray
    ) {
        static RenderStyle rootDefault() {
            return new RenderStyle("#000000", "none", 1.0, 1.0, 1.0, 1.0, "butt", "miter", null);
        }
    }

    private SvgRasterizer() {}

    public static Image loadSvg(InputStream svgStream) {
        return loadSvg(svgStream, DEFAULT_RENDER_SIZE);
    }

    public static Image loadSvg(InputStream svgStream, int targetSizePx) {
        if (svgStream == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(svgStream);
            Element root = doc.getDocumentElement();
            if (root == null || !"svg".equalsIgnoreCase(stripNs(root.getTagName()))) {
                return null;
            }

            double vbX = 0, vbY = 0, vbW = 128, vbH = 128;
            String viewBox = root.getAttribute("viewBox");
            if (viewBox != null && !viewBox.isBlank()) {
                List<Double> nums = parseNumbers(viewBox);
                if (nums.size() >= 4) {
                    vbX = nums.get(0);
                    vbY = nums.get(1);
                    vbW = Math.max(1.0, nums.get(2));
                    vbH = Math.max(1.0, nums.get(3));
                }
            } else {
                double w = parseDimension(root.getAttribute("width"), 128);
                double h = parseDimension(root.getAttribute("height"), 128);
                vbW = Math.max(1.0, w);
                vbH = Math.max(1.0, h);
            }

            int renderPx = Math.max(32, targetSizePx);
            double scale = Math.min(renderPx / vbW, renderPx / vbH);
            int imgW = Math.max(1, (int) Math.round(vbW * scale));
            int imgH = Math.max(1, (int) Math.round(vbH * scale));

            BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.scale(scale, scale);
                g2.translate(-vbX, -vbY);

                Map<String, GradientDef> gradients = new HashMap<>();
                collectDefs(root, gradients);
                renderElementChildren(g2, root, RenderStyle.rootDefault(), gradients);
            } finally {
                g2.dispose();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            ImageIO.write(img, "png", baos);
            return new Image(new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectDefs(Element parent, Map<String, GradientDef> gradients) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element el)) continue;
            String tag = stripNs(el.getTagName()).toLowerCase(Locale.ROOT);
            if ("lineargradient".equals(tag) || "radialgradient".equals(tag)) {
                String id = el.getAttribute("id");
                if (id != null && !id.isBlank()) {
                    GradientDef def = parseGradient(el, tag);
                    if (def != null) {
                        gradients.put(id.trim(), def);
                    }
                }
            } else {
                collectDefs(el, gradients);
            }
        }
    }

    private static GradientDef parseGradient(Element el, String tag) {
        List<Float> fracList = new ArrayList<>();
        List<Color> colorList = new ArrayList<>();
        NodeList stops = el.getChildNodes();
        for (int i = 0; i < stops.getLength(); i++) {
            Node n = stops.item(i);
            if (n instanceof Element stop && "stop".equalsIgnoreCase(stripNs(stop.getTagName()))) {
                double offset = parsePercentOrUnit(stop.getAttribute("offset"), fracList.isEmpty() ? 0.0 : 1.0);
                String stopColor = firstNonBlank(stop.getAttribute("stop-color"), styleAttr(stop, "stop-color"), "#000000");
                double stopOp = parseDouble(firstNonBlank(stop.getAttribute("stop-opacity"), styleAttr(stop, "stop-opacity"), "1"), 1.0);
                Color c = parseColor(stopColor, stopOp);
                if (c != null) {
                    fracList.add((float) Math.max(0.0, Math.min(1.0, offset)));
                    colorList.add(c);
                }
            }
        }
        if (colorList.size() < 2) {
            if (colorList.size() == 1) {
                fracList.add(1.0f);
                colorList.add(colorList.get(0));
            } else {
                return null;
            }
        }
        float[] fractions = new float[fracList.size()];
        Color[] colors = new Color[colorList.size()];
        for (int i = 0; i < fracList.size(); i++) {
            float f = fracList.get(i);
            if (i > 0 && f <= fractions[i - 1]) {
                f = Math.min(1.0f, fractions[i - 1] + 0.001f);
            }
            fractions[i] = f;
            colors[i] = colorList.get(i);
        }

        return new GradientDef(
                tag,
                parsePercentOrUnit(el.getAttribute("x1"), 0.0),
                parsePercentOrUnit(el.getAttribute("y1"), 0.0),
                parsePercentOrUnit(el.getAttribute("x2"), 1.0),
                parsePercentOrUnit(el.getAttribute("y2"), 0.0),
                parsePercentOrUnit(el.getAttribute("cx"), 0.5),
                parsePercentOrUnit(el.getAttribute("cy"), 0.5),
                parsePercentOrUnit(el.getAttribute("r"), 0.5),
                fractions,
                colors
        );
    }

    private static void renderElementChildren(Graphics2D g2, Element parent, RenderStyle inherited, Map<String, GradientDef> gradients) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element child) {
                renderElement(g2, child, inherited, gradients);
            }
        }
    }

    private static void renderElement(Graphics2D g2, Element el, RenderStyle inherited, Map<String, GradientDef> gradients) {
        String tag = stripNs(el.getTagName()).toLowerCase(Locale.ROOT);
        if ("defs".equals(tag) || "title".equals(tag) || "desc".equals(tag) || "metadata".equals(tag)) {
            return;
        }
        if ("none".equalsIgnoreCase(el.getAttribute("display")) || "hidden".equalsIgnoreCase(el.getAttribute("visibility"))) {
            return;
        }

        RenderStyle style = mergeStyle(el, inherited);
        Graphics2D localG = (Graphics2D) g2.create();
        try {
            String transform = el.getAttribute("transform");
            if (transform != null && !transform.isBlank()) {
                AffineTransform at = parseTransform(transform);
                localG.transform(at);
            }

            if ("g".equals(tag) || "svg".equals(tag)) {
                renderElementChildren(localG, el, style, gradients);
                return;
            }

            Shape shape = switch (tag) {
                case "rect" -> buildRect(el);
                case "circle" -> buildCircle(el);
                case "ellipse" -> buildEllipse(el);
                case "line" -> buildLine(el);
                case "polygon" -> buildPolygon(el, true);
                case "polyline" -> buildPolygon(el, false);
                case "path" -> buildPath(el.getAttribute("d"));
                default -> null;
            };
            if (shape == null) return;

            boolean fillable = !"line".equals(tag) && !"polyline".equals(tag);
            if (fillable && style.fill() != null && !"none".equalsIgnoreCase(style.fill())) {
                Paint fillPaint = resolvePaint(style.fill(), style.opacity() * style.fillOpacity(), shape.getBounds2D(), gradients);
                if (fillPaint != null) {
                    localG.setPaint(fillPaint);
                    localG.fill(shape);
                }
            }

            if (style.stroke() != null && !"none".equalsIgnoreCase(style.stroke()) && style.strokeWidth() > 0) {
                Paint strokePaint = resolvePaint(style.stroke(), style.opacity() * style.strokeOpacity(), shape.getBounds2D(), gradients);
                if (strokePaint != null) {
                    localG.setPaint(strokePaint);
                    localG.setStroke(buildStroke(style));
                    localG.draw(shape);
                }
            }
        } finally {
            localG.dispose();
        }
    }

    private static BasicStroke buildStroke(RenderStyle style) {
        int cap = switch (style.strokeLinecap().toLowerCase(Locale.ROOT)) {
            case "round" -> BasicStroke.CAP_ROUND;
            case "square" -> BasicStroke.CAP_SQUARE;
            default -> BasicStroke.CAP_BUTT;
        };
        int join = switch (style.strokeLinejoin().toLowerCase(Locale.ROOT)) {
            case "round" -> BasicStroke.JOIN_ROUND;
            case "bevel" -> BasicStroke.JOIN_BEVEL;
            default -> BasicStroke.JOIN_MITER;
        };
        float width = (float) style.strokeWidth();
        if (style.strokeDasharray() != null && !style.strokeDasharray().isBlank() && !"none".equalsIgnoreCase(style.strokeDasharray())) {
            List<Double> nums = parseNumbers(style.strokeDasharray());
            if (!nums.isEmpty()) {
                float[] dash = new float[nums.size()];
                for (int i = 0; i < nums.size(); i++) {
                    dash[i] = Math.max(0.5f, nums.get(i).floatValue());
                }
                return new BasicStroke(width, cap, join, 10.0f, dash, 0.0f);
            }
        }
        return new BasicStroke(width, cap, join);
    }

    private static Paint resolvePaint(String spec, double alpha, Rectangle2D bounds, Map<String, GradientDef> gradients) {
        String s = spec.trim();
        if (s.startsWith("url(#")) {
            int end = s.indexOf(')');
            String id = (end > 5 ? s.substring(5, end) : s.substring(5)).trim();
            GradientDef g = gradients.get(id);
            if (g == null) return null;

            double bw = Math.max(1.0, bounds.getWidth());
            double bh = Math.max(1.0, bounds.getHeight());
            Color[] alphaColors = new Color[g.colors().length];
            for (int i = 0; i < g.colors().length; i++) {
                Color base = g.colors()[i];
                int a = Math.max(0, Math.min(255, (int) Math.round(base.getAlpha() * alpha)));
                alphaColors[i] = new Color(base.getRed(), base.getGreen(), base.getBlue(), a);
            }
            if ("radialgradient".equals(g.type())) {
                float cx = (float) (bounds.getX() + g.cx() * bw);
                float cy = (float) (bounds.getY() + g.cy() * bh);
                float radius = Math.max(1f, (float) (g.r() * Math.max(bw, bh)));
                return new RadialGradientPaint(cx, cy, radius, g.fractions(), alphaColors);
            }
            float x1 = (float) (bounds.getX() + g.x1() * bw);
            float y1 = (float) (bounds.getY() + g.y1() * bh);
            float x2 = (float) (bounds.getX() + g.x2() * bw);
            float y2 = (float) (bounds.getY() + g.y2() * bh);
            if (Math.abs(x2 - x1) < 0.01f && Math.abs(y2 - y1) < 0.01f) {
                x2 = x1 + 1f;
            }
            if (alphaColors.length == 2 && g.fractions()[0] <= 0.01f && g.fractions()[1] >= 0.99f) {
                return new GradientPaint(x1, y1, alphaColors[0], x2, y2, alphaColors[1]);
            }
            return new java.awt.LinearGradientPaint(x1, y1, x2, y2, g.fractions(), alphaColors);
        }
        return parseColor(s, alpha);
    }

    private static Color parseColor(String raw, double alpha) {
        if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw.trim())) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int a = Math.max(0, Math.min(255, (int) Math.round(255.0 * Math.max(0.0, Math.min(1.0, alpha)))));
        try {
            if (s.startsWith("#")) {
                String hex = s.substring(1);
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1) + hex.charAt(0), 16);
                    int g = Integer.parseInt(hex.substring(1, 2) + hex.charAt(1), 16);
                    int b = Integer.parseInt(hex.substring(2, 3) + hex.charAt(2), 16);
                    return new Color(r, g, b, a);
                } else if (hex.length() == 6) {
                    int rgb = Integer.parseInt(hex, 16);
                    return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, a);
                } else if (hex.length() == 8) {
                    int r = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int b = Integer.parseInt(hex.substring(4, 6), 16);
                    int ca = Integer.parseInt(hex.substring(6, 8), 16);
                    return new Color(r, g, b, (int) Math.round(ca * alpha));
                }
            } else if (s.startsWith("rgb")) {
                List<Double> nums = parseNumbers(s);
                if (nums.size() >= 3) {
                    int r = Math.max(0, Math.min(255, nums.get(0).intValue()));
                    int g = Math.max(0, Math.min(255, nums.get(1).intValue()));
                    int b = Math.max(0, Math.min(255, nums.get(2).intValue()));
                    double extraA = nums.size() >= 4 ? nums.get(3) : 1.0;
                    int ca = Math.max(0, Math.min(255, (int) Math.round(255.0 * alpha * extraA)));
                    return new Color(r, g, b, ca);
                }
            } else {
                return switch (s) {
                    case "white" -> new Color(255, 255, 255, a);
                    case "black" -> new Color(0, 0, 0, a);
                    case "red" -> new Color(220, 38, 38, a);
                    case "green" -> new Color(0, 138, 50, a);
                    case "blue" -> new Color(37, 99, 235, a);
                    case "yellow" -> new Color(250, 204, 21, a);
                    case "orange" -> new Color(249, 115, 22, a);
                    case "gray", "grey" -> new Color(100, 116, 139, a);
                    case "transparent" -> new Color(0, 0, 0, 0);
                    default -> new Color(0, 0, 0, a);
                };
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Shape buildRect(Element el) {
        double x = parseDouble(el.getAttribute("x"), 0);
        double y = parseDouble(el.getAttribute("y"), 0);
        double w = parseDouble(el.getAttribute("width"), 0);
        double h = parseDouble(el.getAttribute("height"), 0);
        double rx = parseDouble(el.getAttribute("rx"), 0);
        double ry = parseDouble(el.getAttribute("ry"), rx);
        if (rx <= 0 && ry > 0) rx = ry;
        if (rx > 0 || ry > 0) {
            return new RoundRectangle2D.Double(x, y, w, h, rx * 2.0, ry * 2.0);
        }
        return new Rectangle2D.Double(x, y, w, h);
    }

    private static Shape buildCircle(Element el) {
        double cx = parseDouble(el.getAttribute("cx"), 0);
        double cy = parseDouble(el.getAttribute("cy"), 0);
        double r = parseDouble(el.getAttribute("r"), 0);
        return new Ellipse2D.Double(cx - r, cy - r, r * 2.0, r * 2.0);
    }

    private static Shape buildEllipse(Element el) {
        double cx = parseDouble(el.getAttribute("cx"), 0);
        double cy = parseDouble(el.getAttribute("cy"), 0);
        double rx = parseDouble(el.getAttribute("rx"), 0);
        double ry = parseDouble(el.getAttribute("ry"), 0);
        return new Ellipse2D.Double(cx - rx, cy - ry, rx * 2.0, ry * 2.0);
    }

    private static Shape buildLine(Element el) {
        double x1 = parseDouble(el.getAttribute("x1"), 0);
        double y1 = parseDouble(el.getAttribute("y1"), 0);
        double x2 = parseDouble(el.getAttribute("x2"), 0);
        double y2 = parseDouble(el.getAttribute("y2"), 0);
        return new Line2D.Double(x1, y1, x2, y2);
    }

    private static Shape buildPolygon(Element el, boolean close) {
        List<Double> pts = parseNumbers(el.getAttribute("points"));
        if (pts.size() < 4) return null;
        Path2D.Double path = new Path2D.Double();
        path.moveTo(pts.get(0), pts.get(1));
        for (int i = 2; i + 1 < pts.size(); i += 2) {
            path.lineTo(pts.get(i), pts.get(i + 1));
        }
        if (close) path.closePath();
        return path;
    }

    private static Shape buildPath(String d) {
        if (d == null || d.isBlank()) return null;
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        List<String> tokens = tokenizePath(d);
        int i = 0;
        char cmd = 0;
        double curX = 0, curY = 0, startX = 0, startY = 0;
        double lastCtrlX = 0, lastCtrlY = 0;
        char prevCmd = 0;

        while (i < tokens.size()) {
            String tok = tokens.get(i);
            if (tok.length() == 1 && Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    path.closePath();
                    curX = startX;
                    curY = startY;
                    prevCmd = cmd;
                    continue;
                }
            } else if (cmd == 0) {
                i++;
                continue;
            } else if (cmd == 'M') {
                cmd = 'L';
            } else if (cmd == 'm') {
                cmd = 'l';
            }

            switch (cmd) {
                case 'M', 'm' -> {
                    if (i + 1 >= tokens.size()) return path;
                    double x = Double.parseDouble(tokens.get(i++));
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 'm') { x += curX; y += curY; }
                    path.moveTo(x, y);
                    curX = startX = x;
                    curY = startY = y;
                    prevCmd = cmd;
                }
                case 'L', 'l' -> {
                    if (i + 1 >= tokens.size()) return path;
                    double x = Double.parseDouble(tokens.get(i++));
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 'l') { x += curX; y += curY; }
                    path.lineTo(x, y);
                    curX = x; curY = y;
                    prevCmd = cmd;
                }
                case 'H', 'h' -> {
                    if (i >= tokens.size()) return path;
                    double x = Double.parseDouble(tokens.get(i++));
                    if (cmd == 'h') x += curX;
                    path.lineTo(x, curY);
                    curX = x;
                    prevCmd = cmd;
                }
                case 'V', 'v' -> {
                    if (i >= tokens.size()) return path;
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 'v') y += curY;
                    path.lineTo(curX, y);
                    curY = y;
                    prevCmd = cmd;
                }
                case 'C', 'c' -> {
                    if (i + 5 >= tokens.size()) return path;
                    double x1 = Double.parseDouble(tokens.get(i++));
                    double y1 = Double.parseDouble(tokens.get(i++));
                    double x2 = Double.parseDouble(tokens.get(i++));
                    double y2 = Double.parseDouble(tokens.get(i++));
                    double x = Double.parseDouble(tokens.get(i++));
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 'c') {
                        x1 += curX; y1 += curY;
                        x2 += curX; y2 += curY;
                        x += curX; y += curY;
                    }
                    path.curveTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2; lastCtrlY = y2;
                    curX = x; curY = y;
                    prevCmd = cmd;
                }
                case 'S', 's' -> {
                    if (i + 3 >= tokens.size()) return path;
                    double x1 = (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's')
                            ? 2 * curX - lastCtrlX : curX;
                    double y1 = (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's')
                            ? 2 * curY - lastCtrlY : curY;
                    double x2 = Double.parseDouble(tokens.get(i++));
                    double y2 = Double.parseDouble(tokens.get(i++));
                    double x = Double.parseDouble(tokens.get(i++));
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 's') {
                        x2 += curX; y2 += curY;
                        x += curX; y += curY;
                    }
                    path.curveTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2; lastCtrlY = y2;
                    curX = x; curY = y;
                    prevCmd = cmd;
                }
                case 'Q', 'q' -> {
                    if (i + 3 >= tokens.size()) return path;
                    double x1 = Double.parseDouble(tokens.get(i++));
                    double y1 = Double.parseDouble(tokens.get(i++));
                    double x = Double.parseDouble(tokens.get(i++));
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 'q') {
                        x1 += curX; y1 += curY;
                        x += curX; y += curY;
                    }
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1; lastCtrlY = y1;
                    curX = x; curY = y;
                    prevCmd = cmd;
                }
                case 'T', 't' -> {
                    if (i + 1 >= tokens.size()) return path;
                    double x1 = (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't')
                            ? 2 * curX - lastCtrlX : curX;
                    double y1 = (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't')
                            ? 2 * curY - lastCtrlY : curY;
                    double x = Double.parseDouble(tokens.get(i++));
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 't') {
                        x += curX; y += curY;
                    }
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1; lastCtrlY = y1;
                    curX = x; curY = y;
                    prevCmd = cmd;
                }
                case 'A', 'a' -> {
                    if (i + 6 >= tokens.size()) return path;
                    double rx = Double.parseDouble(tokens.get(i++));
                    double ry = Double.parseDouble(tokens.get(i++));
                    double xRot = Double.parseDouble(tokens.get(i++));
                    boolean largeArc = Double.parseDouble(tokens.get(i++)) != 0.0;
                    boolean sweep = Double.parseDouble(tokens.get(i++)) != 0.0;
                    double x = Double.parseDouble(tokens.get(i++));
                    double y = Double.parseDouble(tokens.get(i++));
                    if (cmd == 'a') { x += curX; y += curY; }
                    appendArc(path, curX, curY, rx, ry, xRot, largeArc, sweep, x, y);
                    curX = x; curY = y;
                    prevCmd = cmd;
                }
                default -> i++;
            }
        }
        return path;
    }

    private static void appendArc(Path2D.Double path, double x0, double y0,
                                  double rxIn, double ryIn, double angleDeg,
                                  boolean largeArc, boolean sweep, double x, double y) {
        if (x0 == x && y0 == y) return;
        if (rxIn == 0 || ryIn == 0) {
            path.lineTo(x, y);
            return;
        }
        double rx = Math.abs(rxIn);
        double ry = Math.abs(ryIn);
        double angle = Math.toRadians(angleDeg % 360.0);
        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);
        double dx2 = (x0 - x) / 2.0;
        double dy2 = (y0 - y) / 2.0;
        double x1 = cosA * dx2 + sinA * dy2;
        double y1 = -sinA * dx2 + cosA * dy2;

        double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        if (lambda > 1.0) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }

        double rxSq = rx * rx;
        double rySq = ry * ry;
        double x1Sq = x1 * x1;
        double y1Sq = y1 * y1;
        double num = Math.max(0.0, rxSq * rySq - rxSq * y1Sq - rySq * x1Sq);
        double den = rxSq * y1Sq + rySq * x1Sq;
        double factor = den == 0 ? 0 : Math.sqrt(num / den);
        if (largeArc == sweep) factor = -factor;

        double cx1 = factor * (rx * y1 / ry);
        double cy1 = factor * (-ry * x1 / rx);
        double cx = cosA * cx1 - sinA * cy1 + (x0 + x) / 2.0;
        double cy = sinA * cx1 + cosA * cy1 + (y0 + y) / 2.0;

        double vx1 = (x1 - cx1) / rx;
        double vy1 = (y1 - cy1) / ry;
        double vx2 = (-x1 - cx1) / rx;
        double vy2 = (-y1 - cy1) / ry;
        double startDeg = Math.toDegrees(Math.atan2(vy1, vx1));
        double dAngle = Math.toDegrees(Math.atan2(vx1 * vy2 - vy1 * vx2, vx1 * vx2 + vy1 * vy2));
        if (!sweep && dAngle > 0) dAngle -= 360.0;
        if (sweep && dAngle < 0) dAngle += 360.0;

        Arc2D.Double arc = new Arc2D.Double(-rx, -ry, 2 * rx, 2 * ry, -startDeg, -dAngle, Arc2D.OPEN);
        AffineTransform at = AffineTransform.getTranslateInstance(cx, cy);
        at.rotate(angle);
        path.append(at.createTransformedShape(arc), true);
    }

    private static List<String> tokenizePath(String d) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[a-zA-Z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").matcher(d);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static AffineTransform parseTransform(String transform) {
        AffineTransform result = new AffineTransform();
        Matcher m = TRANSFORM_FN.matcher(transform);
        while (m.find()) {
            String fn = m.group(1).toLowerCase(Locale.ROOT);
            List<Double> nums = parseNumbers(m.group(2));
            switch (fn) {
                case "translate" -> {
                    double tx = !nums.isEmpty() ? nums.get(0) : 0;
                    double ty = nums.size() > 1 ? nums.get(1) : 0;
                    result.translate(tx, ty);
                }
                case "scale" -> {
                    double sx = !nums.isEmpty() ? nums.get(0) : 1;
                    double sy = nums.size() > 1 ? nums.get(1) : sx;
                    result.scale(sx, sy);
                }
                case "rotate" -> {
                    if (!nums.isEmpty()) {
                        double rad = Math.toRadians(nums.get(0));
                        if (nums.size() >= 3) {
                            result.rotate(rad, nums.get(1), nums.get(2));
                        } else {
                            result.rotate(rad);
                        }
                    }
                }
                case "matrix" -> {
                    if (nums.size() >= 6) {
                        result.concatenate(new AffineTransform(
                                nums.get(0), nums.get(1), nums.get(2),
                                nums.get(3), nums.get(4), nums.get(5)));
                    }
                }
            }
        }
        return result;
    }

    private static RenderStyle mergeStyle(Element el, RenderStyle parent) {
        String fill = firstNonBlank(el.getAttribute("fill"), styleAttr(el, "fill"), parent.fill());
        String stroke = firstNonBlank(el.getAttribute("stroke"), styleAttr(el, "stroke"), parent.stroke());
        double strokeW = parseDouble(firstNonBlank(el.getAttribute("stroke-width"), styleAttr(el, "stroke-width"), null), parent.strokeWidth());
        double op = parent.opacity() * parseDouble(firstNonBlank(el.getAttribute("opacity"), styleAttr(el, "opacity"), null), 1.0);
        double fillOp = parseDouble(firstNonBlank(el.getAttribute("fill-opacity"), styleAttr(el, "fill-opacity"), null), parent.fillOpacity());
        double strokeOp = parseDouble(firstNonBlank(el.getAttribute("stroke-opacity"), styleAttr(el, "stroke-opacity"), null), parent.strokeOpacity());
        String cap = firstNonBlank(el.getAttribute("stroke-linecap"), styleAttr(el, "stroke-linecap"), parent.strokeLinecap());
        String join = firstNonBlank(el.getAttribute("stroke-linejoin"), styleAttr(el, "stroke-linejoin"), parent.strokeLinejoin());
        String dash = firstNonBlank(el.getAttribute("stroke-dasharray"), styleAttr(el, "stroke-dasharray"), parent.strokeDasharray());
        return new RenderStyle(fill, stroke, strokeW, op, fillOp, strokeOp, cap, join, dash);
    }

    private static String styleAttr(Element el, String prop) {
        String style = el.getAttribute("style");
        if (style == null || style.isBlank()) return null;
        for (String decl : style.split(";")) {
            int colon = decl.indexOf(':');
            if (colon > 0 && decl.substring(0, colon).trim().equalsIgnoreCase(prop)) {
                return decl.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static List<Double> parseNumbers(String text) {
        List<Double> nums = new ArrayList<>();
        if (text == null || text.isBlank()) return nums;
        Matcher m = NUMBER_PATTERN.matcher(text);
        while (m.find()) {
            try {
                nums.add(Double.parseDouble(m.group()));
            } catch (NumberFormatException ignored) {}
        }
        return nums;
    }

    private static double parsePercentOrUnit(String s, double def) {
        if (s == null || s.isBlank()) return def;
        String t = s.trim();
        if (t.endsWith("%")) {
            return parseDouble(t.substring(0, t.length() - 1), def * 100.0) / 100.0;
        }
        return parseDouble(t, def);
    }

    private static double parseDimension(String s, double def) {
        if (s == null || s.isBlank()) return def;
        String clean = s.trim().replaceAll("[^0-9.+-]", "");
        return parseDouble(clean, def);
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String firstNonBlank(String a, String b, String def) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return def;
    }

    private static String stripNs(String tag) {
        int idx = tag.indexOf(':');
        return idx >= 0 ? tag.substring(idx + 1) : tag;
    }
}

