package com.smartkhata.util;

import com.smartkhata.app.AppConfig;
import javafx.scene.image.Image;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves product images from explicit classpath presets, local persistent image files,
 * or keyword-matched grocery fallback icons with thread-safe caching.
 */
public final class ProductImageResolver {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    public static final Map<String, String> PRESET_IMAGES = new LinkedHashMap<>();

    static {
        PRESET_IMAGES.put("Soybean Oil Bottle SVG (সয়াবিন তেল)", "/com/smartkhata/ui/images/products_svg/soyabean_oil.svg");
        PRESET_IMAGES.put("Mustard Oil Bottle SVG (সরিষার তেল)", "/com/smartkhata/ui/images/products_svg/mustard_oil.svg");
        PRESET_IMAGES.put("Miniket Rice Sack SVG (চাল)", "/com/smartkhata/ui/images/products_svg/miniket_rice.svg");
        PRESET_IMAGES.put("Refined Sugar Pack SVG (চিনি)", "/com/smartkhata/ui/images/products_svg/sugar_pack.svg");
        PRESET_IMAGES.put("Whole Wheat Atta SVG (আটা / ময়দা)", "/com/smartkhata/ui/images/products_svg/atta_flour.svg");
        PRESET_IMAGES.put("Iodized Salt Pouch SVG (লবণ)", "/com/smartkhata/ui/images/products_svg/iodized_salt.svg");
        PRESET_IMAGES.put("Masoor Dal Bowl SVG (ডাল)", "/com/smartkhata/ui/images/products_svg/masoor_dal.svg");
        PRESET_IMAGES.put("Milk Powder Canister SVG (গুঁড়া দুধ)", "/com/smartkhata/ui/images/products_svg/milk_powder.svg");
        PRESET_IMAGES.put("Black Tea Box SVG (চা পাতা)", "/com/smartkhata/ui/images/products_svg/tea_leaf.svg");
        PRESET_IMAGES.put("Dishwash Liquid SVG (ডিশওয়াশ)", "/com/smartkhata/ui/images/products_svg/dish_wash.svg");
        PRESET_IMAGES.put("Rice Bowl (চাল)", "/com/smartkhata/ui/images/pos/rice_bowl.png");
        PRESET_IMAGES.put("Cooking Oil (ভোজ্য তেল)", "/com/smartkhata/ui/images/pos/cooking_oil.png");
        PRESET_IMAGES.put("Sugar Bowl (চিনি)", "/com/smartkhata/ui/images/pos/sugar_bowl.png");
        PRESET_IMAGES.put("Flour Sack (আটা / ময়দা)", "/com/smartkhata/ui/images/pos/flour_sack.png");
        PRESET_IMAGES.put("Salt Pack (লবণ)", "/com/smartkhata/ui/images/pos/salt_pack.png");
        PRESET_IMAGES.put("Lentil Bowl (ডাল)", "/com/smartkhata/ui/images/pos/lentil_bowl.png");
        PRESET_IMAGES.put("General Grocery Box", "/com/smartkhata/ui/images/grocery/inventory_cube.png");
    }

    private ProductImageResolver() {}

    public static Image resolve(String imagePath, String productName, String categoryName) {
        if (imagePath != null && !imagePath.trim().isBlank()) {
            Image explicit = loadByPath(imagePath.trim());
            if (explicit != null && !explicit.isError()) {
                return explicit;
            }
        }
        String fallbackResource = inferPresetPath(productName, categoryName);
        return loadClasspathImage(fallbackResource);
    }

    public static String inferPresetPath(String productName, String categoryName) {
        String key = ((productName != null ? productName : "") + " "
                + (categoryName != null ? categoryName : "")).toLowerCase(Locale.ROOT);

        if (key.contains("mustard") || key.contains("sorisha") || key.contains("সরিষা")) {
            return "/com/smartkhata/ui/images/products_svg/mustard_oil.svg";
        } else if (key.contains("rice") || key.contains("miniket") || key.contains("nazir") || key.contains("basmati")
                || key.contains("chinigura") || key.contains("chal") || key.contains("চাল")) {
            return "/com/smartkhata/ui/images/products_svg/miniket_rice.svg";
        } else if (key.contains("oil") || key.contains("soybean") || key.contains("soyabean")
                || key.contains("tel") || key.contains("তেল")) {
            return "/com/smartkhata/ui/images/products_svg/soyabean_oil.svg";
        } else if (key.contains("sugar") || key.contains("chini") || key.contains("চিনি") || key.contains("gur")) {
            return "/com/smartkhata/ui/images/products_svg/sugar_pack.svg";
        } else if (key.contains("flour") || key.contains("atta") || key.contains("maida")
                || key.contains("suji") || key.contains("আটা") || key.contains("ময়দা")) {
            return "/com/smartkhata/ui/images/products_svg/atta_flour.svg";
        } else if (key.contains("salt") || key.contains("lobon") || key.contains("লবণ")) {
            return "/com/smartkhata/ui/images/products_svg/iodized_salt.svg";
        } else if (key.contains("dal") || key.contains("lentil") || key.contains("masoor")
                || key.contains("moshur") || key.contains("moong") || key.contains("ডাল")) {
            return "/com/smartkhata/ui/images/products_svg/masoor_dal.svg";
        } else if (key.contains("milk") || key.contains("dairy") || key.contains("dugdha") || key.contains("দুধ")) {
            return "/com/smartkhata/ui/images/products_svg/milk_powder.svg";
        } else if (key.contains("tea") || key.contains("mirzapore") || key.contains("চা")) {
            return "/com/smartkhata/ui/images/products_svg/tea_leaf.svg";
        } else if (key.contains("dish") || key.contains("vim") || key.contains("soap") || key.contains("detergent") || key.contains("wash")) {
            return "/com/smartkhata/ui/images/products_svg/dish_wash.svg";
        }
        return "/com/smartkhata/ui/images/grocery/inventory_cube.png";
    }

    public static String findPresetLabelForPath(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return null;
        for (Map.Entry<String, String> entry : PRESET_IMAGES.entrySet()) {
            if (entry.getValue().equals(imagePath.trim())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Image loadByPath(String path) {
        if (path.startsWith("/com/")) {
            return loadClasspathImage(path);
        }
        return CACHE.computeIfAbsent("file:" + path, k -> {
            try {
                Path candidate = Paths.get(path);
                if (!candidate.isAbsolute()) {
                    candidate = AppConfig.getProductImagesDir().resolve(path);
                }
                if (!Files.exists(candidate)) {
                    Path fallback = AppConfig.getProductImagesDir().resolve(candidate.getFileName().toString());
                    if (Files.exists(fallback)) {
                        candidate = fallback;
                    }
                }
                if (Files.exists(candidate)) {
                    try (InputStream is = Files.newInputStream(candidate)) {
                        if (isSvgPath(candidate.getFileName().toString())) {
                            return SvgRasterizer.loadSvg(is);
                        }
                        return new Image(is);
                    }
                }
            } catch (Exception ignored) {}
            return null;
        });
    }

    private static Image loadClasspathImage(String resourcePath) {
        return CACHE.computeIfAbsent("cp:" + resourcePath, k -> {
            try (InputStream is = ProductImageResolver.class.getResourceAsStream(resourcePath)) {
                if (is == null) return null;
                if (isSvgPath(resourcePath)) {
                    return SvgRasterizer.loadSvg(is);
                }
                return new Image(is);
            } catch (Exception e) {
                return null;
            }
        });
    }

    private static boolean isSvgPath(String path) {
        return path != null && path.trim().toLowerCase(Locale.ROOT).endsWith(".svg");
    }
}

