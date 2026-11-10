package com.smartkhata.ui;

import com.smartkhata.controller.CustomerListController;
import com.smartkhata.controller.DashboardController;
import com.smartkhata.controller.ExpensesController;
import com.smartkhata.controller.InventoryController;
import com.smartkhata.controller.MainShellController;
import com.smartkhata.controller.PaymentsController;
import com.smartkhata.controller.PurchasesController;
import com.smartkhata.controller.ReportsController;
import com.smartkhata.controller.SalesController;
import com.smartkhata.controller.SupplierListController;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reusable view router managing navigation and controller synchronization between SmartKhata modules.
 */
public final class ViewRouter {

    private static final Logger log = LoggerFactory.getLogger(ViewRouter.class);

    public enum ViewId {
        DASHBOARD,
        CUSTOMERS,
        SUPPLIERS,
        SALES,
        PURCHASES,
        PAYMENTS,
        EXPENSES,
        INVENTORY,
        REPORTS,
        SETTINGS
    }

    private static StackPane contentContainer;
    private static final Map<ViewId, Node> viewCache = new EnumMap<>(ViewId.class);
    private static final Map<ViewId, Object> controllerCache = new EnumMap<>(ViewId.class);
    private static ViewId currentView;
    private static Consumer<ViewId> onNavigateListener;
    private static MainShellController activeMainShell;

    private ViewRouter() {}

    public static void registerMainShell(MainShellController shell) {
        activeMainShell = shell;
    }

    public static void setContainer(StackPane container) {
        shutdownControllers();
        contentContainer = container;
        currentView = null;
    }

    public static void shutdownControllers() {
        Object dashCtrl = controllerCache.get(ViewId.DASHBOARD);
        if (dashCtrl instanceof DashboardController dc) {
            dc.shutdown();
        }
        Object custCtrl = controllerCache.get(ViewId.CUSTOMERS);
        if (custCtrl instanceof CustomerListController clc) {
            clc.shutdown();
        }
        Object supCtrl = controllerCache.get(ViewId.SUPPLIERS);
        if (supCtrl instanceof SupplierListController slc) {
            slc.shutdown();
        }
        Object salesCtrl = controllerCache.get(ViewId.SALES);
        if (salesCtrl instanceof SalesController sc) {
            sc.shutdown();
        }
        if (activeMainShell != null) {
            activeMainShell.shutdown();
            activeMainShell = null;
        }
        viewCache.clear();
        controllerCache.clear();
        currentView = null;
    }

    public static void setOnNavigateListener(Consumer<ViewId> listener) {
        onNavigateListener = listener;
    }

    public static ViewId getCurrentView() {
        return currentView;
    }

    public static DashboardController getDashboardController() {
        Object ctrl = controllerCache.get(ViewId.DASHBOARD);
        return (ctrl instanceof DashboardController dc) ? dc : null;
    }

    /**
     * Refreshes all cached views affected by POS order mutations (Dashboard, Sales, Reports, Inventory, Customers).
     */
    public static void refreshSalesImpactViews() {
        DashboardController dc = getDashboardController();
        if (dc != null) {
            dc.refreshDashboard();
        }
        if (controllerCache.get(ViewId.SALES) instanceof SalesController sc) {
            sc.refreshData();
        }
        if (controllerCache.get(ViewId.INVENTORY) instanceof InventoryController ic) {
            ic.refreshData();
        }
        if (controllerCache.get(ViewId.CUSTOMERS) instanceof CustomerListController clc) {
            clc.refreshData();
        }
        if (controllerCache.get(ViewId.REPORTS) instanceof ReportsController rc) {
            rc.refreshData();
        }
    }

    public static void navigateTo(ViewId viewId) {
        if (contentContainer == null) {
            log.error("Cannot navigate: content container is not initialized");
            return;
        }

        log.debug("Navigating to view: {}", viewId);

        boolean wasAlreadyCached = viewCache.containsKey(viewId);
        Node viewNode = viewCache.computeIfAbsent(viewId, ViewRouter::loadView);
        if (viewNode != null) {
            contentContainer.getChildren().setAll(viewNode);
            currentView = viewId;

            if (onNavigateListener != null) {
                onNavigateListener.accept(viewId);
            }

            if (wasAlreadyCached) {
                if (viewId == ViewId.DASHBOARD) {
                    DashboardController dc = getDashboardController();
                    if (dc != null) {
                        dc.refreshDashboard();
                    }
                } else if (viewId == ViewId.CUSTOMERS) {
                    Object custCtrl = controllerCache.get(ViewId.CUSTOMERS);
                    if (custCtrl instanceof CustomerListController clc) {
                        clc.refreshData();
                    }
                } else if (viewId == ViewId.SUPPLIERS) {
                    Object supCtrl = controllerCache.get(ViewId.SUPPLIERS);
                    if (supCtrl instanceof SupplierListController slc) {
                        slc.refreshData();
                    }
                } else if (viewId == ViewId.SALES) {
                    Object salesCtrl = controllerCache.get(ViewId.SALES);
                    if (salesCtrl instanceof SalesController sc) {
                        sc.refreshData();
                    }
                } else if (viewId == ViewId.PURCHASES) {
                    Object purCtrl = controllerCache.get(ViewId.PURCHASES);
                    if (purCtrl instanceof PurchasesController pc) {
                        pc.refreshAll();
                    }
                } else if (viewId == ViewId.PAYMENTS) {
                    Object payCtrl = controllerCache.get(ViewId.PAYMENTS);
                    if (payCtrl instanceof PaymentsController pyc) {
                        pyc.loadPayments();
                    }
                } else if (viewId == ViewId.EXPENSES) {
                    Object expCtrl = controllerCache.get(ViewId.EXPENSES);
                    if (expCtrl instanceof ExpensesController ec) {
                        ec.refreshAll();
                    }
                } else if (viewId == ViewId.INVENTORY) {
                    Object invCtrl = controllerCache.get(ViewId.INVENTORY);
                    if (invCtrl instanceof InventoryController ic) {
                        ic.refreshData();
                    }
                } else if (viewId == ViewId.REPORTS) {
                    Object repCtrl = controllerCache.get(ViewId.REPORTS);
                    if (repCtrl instanceof ReportsController rc) {
                        rc.refreshData();
                    }
                }
            }
        }
    }

    private static Node loadView(ViewId viewId) {
        String fxmlPath = switch (viewId) {
            case DASHBOARD -> "/com/smartkhata/ui/views/DashboardView.fxml";
            case CUSTOMERS -> "/com/smartkhata/ui/views/CustomerListView.fxml";
            case SUPPLIERS -> "/com/smartkhata/ui/views/SupplierListView.fxml";
            case INVENTORY -> "/com/smartkhata/ui/views/InventoryView.fxml";
            case SALES -> "/com/smartkhata/ui/views/SalesView.fxml";
            case PURCHASES -> "/com/smartkhata/ui/views/PurchasesView.fxml";
            case PAYMENTS -> "/com/smartkhata/ui/views/PaymentsView.fxml";
            case EXPENSES -> "/com/smartkhata/ui/views/ExpensesView.fxml";
            case REPORTS -> "/com/smartkhata/ui/views/ReportsView.fxml";
            case SETTINGS -> "/com/smartkhata/ui/views/SettingsView.fxml";
        };

        try {
            URL url = ViewRouter.class.getResource(fxmlPath);
            if (url != null) {
                FXMLLoader loader = new FXMLLoader(url);
                Node loaded = loader.load();
                Object controller = loader.getController();
                if (controller != null) {
                    controllerCache.put(viewId, controller);
                }
                return loaded;
            }
        } catch (IOException e) {
            log.error("Failed to load {}: {}", fxmlPath, e.getMessage(), e);
        }

        return createPlaceholder(viewId);
    }

    private static Node createPlaceholder(ViewId viewId) {
        VBox box = new VBox(16);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-padding: 60px; -fx-background-color: -color-surface-bg;");

        Label titleLabel = new Label(viewId.name());
        titleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: -color-fg-default;");

        Label descLabel = new Label("Unable to load module view.");
        descLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: -color-fg-muted;");

        box.getChildren().addAll(titleLabel, descLabel);
        return box;
    }
}
