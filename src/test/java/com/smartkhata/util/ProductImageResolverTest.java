package com.smartkhata.util;

import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductImageResolverTest {

    private static final int MAX_SVG_BYTES = 25 * 1024;

    private static final List<String> PRODUCT_SVG_RESOURCES = List.of(
            "/com/smartkhata/ui/images/products_svg/soyabean_oil.svg",
            "/com/smartkhata/ui/images/products_svg/mustard_oil.svg",
            "/com/smartkhata/ui/images/products_svg/miniket_rice.svg",
            "/com/smartkhata/ui/images/products_svg/sugar_pack.svg",
            "/com/smartkhata/ui/images/products_svg/atta_flour.svg",
            "/com/smartkhata/ui/images/products_svg/iodized_salt.svg",
            "/com/smartkhata/ui/images/products_svg/masoor_dal.svg",
            "/com/smartkhata/ui/images/products_svg/milk_powder.svg",
            "/com/smartkhata/ui/images/products_svg/tea_leaf.svg",
            "/com/smartkhata/ui/images/products_svg/dish_wash.svg"
    );

    @Test
    void allProductSvgImagesShouldExistStayUnder25KbAndRenderIntoJavaFxImage() throws Exception {
        for (String resourcePath : PRODUCT_SVG_RESOURCES) {
            Path diskPath = Paths.get("src/main/resources" + resourcePath);
            assertThat(Files.exists(diskPath))
                    .withFailMessage("Missing SVG file on disk: %s", diskPath)
                    .isTrue();

            long size = Files.size(diskPath);
            assertThat(size)
                    .withFailMessage("SVG %s size %d bytes must stay > 300B and <= 25KB", resourcePath, size)
                    .isGreaterThan(300L)
                    .isLessThanOrEqualTo(MAX_SVG_BYTES);

            try (InputStream is = ProductImageResolverTest.class.getResourceAsStream(resourcePath)) {
                assertThat(is).isNotNull();
            }

            Image img = ProductImageResolver.resolve(resourcePath, "Sample Grocery Item", "General");
            assertThat(img)
                    .withFailMessage("SVG failed to rasterize into JavaFX Image: %s", resourcePath)
                    .isNotNull();
            assertThat(img.isError())
                    .withFailMessage("Rasterized JavaFX Image reported error for %s", resourcePath)
                    .isFalse();
            assertThat(img.getWidth()).isGreaterThanOrEqualTo(100.0);
            assertThat(img.getHeight()).isGreaterThanOrEqualTo(100.0);
        }
    }

    @Test
    void keywordInferenceShouldMapGroceryItemsToSvgArtworks() {
        assertThat(ProductImageResolver.inferPresetPath("5 Liter Soyabean Oil", "Oil"))
                .isEqualTo("/com/smartkhata/ui/images/products_svg/soyabean_oil.svg");
        assertThat(ProductImageResolver.inferPresetPath("Radhuni Pure Mustard Oil", "Oil"))
                .isEqualTo("/com/smartkhata/ui/images/products_svg/mustard_oil.svg");
        assertThat(ProductImageResolver.inferPresetPath("Miniket Rice 25kg", "Rice"))
                .isEqualTo("/com/smartkhata/ui/images/products_svg/miniket_rice.svg");
        assertThat(ProductImageResolver.inferPresetPath("Diploma Milk Powder", "Dairy"))
                .isEqualTo("/com/smartkhata/ui/images/products_svg/milk_powder.svg");
        assertThat(ProductImageResolver.inferPresetPath("Ispahani Mirzapore Tea", "Tea"))
                .isEqualTo("/com/smartkhata/ui/images/products_svg/tea_leaf.svg");
        assertThat(ProductImageResolver.inferPresetPath("Vim Dishwash Liquid", "Cleaning"))
                .isEqualTo("/com/smartkhata/ui/images/products_svg/dish_wash.svg");
    }
}

