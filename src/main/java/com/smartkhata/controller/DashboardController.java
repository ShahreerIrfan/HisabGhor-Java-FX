package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.service.api.DashboardService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteDashboardService;
import com.smartkhata.ui.ViewRouter;
import com.smartkhata.util.BengaliMoneyFormatter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller for the primary live business dashboard displaying real-time
 * SQLite calculations, responsive background queries, and connected quick actions.
 */
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final String UP_ARROW_PATH = "M12 4l-6 6h4v8h4v-8h4l-6-6z";
    private static final String DOWN_ARROW_PATH = "M12 20l6-6h-4V6h-4v8H6l6 6z";

    private final DashboardService dashboardService;
    private final ExecutorService executor;
    private Image lowStockEmptyImage;

    // Welcome Header
    @FXML private Label lblWelcomeGreeting;
    @FXML private Label lblDashboardSubtitle;

    // Top Summary KPI Cards
    @FXML private Label totalSalesLabel;
    @FXML private SVGPath salesArrowIcon;
    @FXML private Label salesGrowthLabel;
    @FXML private Label dueCollectionLabel;
    @FXML private SVGPath collectionArrowIcon;
    @FXML private Label collectionGrowthLabel;
    @FXML private Label expensesLabel;
    @FXML private SVGPath expenseArrowIcon;
    @FXML private Label expenseGrowthLabel;
    @FXML private Label inventoryValueLabel;
    @FXML private Label stockCountLabel;

    // Line Chart & Trend Section
    @FXML private Label lblTrendChartTitle;
    @FXML private Label lblTrendChartSubtitle;
    @FXML private LineChart<String, Number> revenueTrendChart;
    @FXML private CategoryAxis xAxis;
    @FXML private NumberAxis yAxis;
    @FXML private ToggleButton btnTrendSales;
    @FXML private ToggleButton btnTrendCollection;
    @FXML private ToggleButton btnTrendExpenses;
    @FXML private ToggleGroup trendGroup;
    @FXML private VBox trendEmptyOverlay;
    @FXML private Label lblEmptyTrendTitle;
    @FXML private Label lblEmptyTrendSubtitle;
    @FXML private Button btnEmptyTrendAction;

    @FXML private Label lblChartMetricTitle;
    @FXML private Label chartTotalSalesLabel;
    @FXML private Label chartGrowthLabel;
    @FXML private Label chartAvgDailySalesLabel;
    @FXML private Label lblChartCountTitle;
    @FXML private Label chartTotalInvoicesLabel;

    // Recent Transactions Table
    @FXML private TableView<RecentTransactionDto> recentTransactionsTable;
    @FXML private TableColumn<RecentTransactionDto, String> colDate;
    @FXML private TableColumn<RecentTransactionDto, String> colCustomer;
    @FXML private TableColumn<RecentTransactionDto, String> colType;
    @FXML private TableColumn<RecentTransactionDto, String> colAmount;
    @FXML private TableColumn<RecentTransactionDto, String> colStatus;

    // Right Column Widgets
    @FXML private Label totalBakiLabel;
    @FXML private Label bakiCustomerCountLabel;
    @FXML private Label lowStockBadgeLabel;
    @FXML private VBox lowStockListContainer;

    private DashboardService.TrendMetric activeMetric = DashboardService.TrendMetric.SALES;
    private LocalDate activeStartDate;
    private LocalDate activeEndDate;
    private String activePeriodSubtitle = "for this month";
    private volatile CompletableFuture<Void> currentLoadFuture = CompletableFuture.completedFuture(null);

    public DashboardController() {
        this(new SqliteDashboardService());
    }

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "dashboard-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @FXML
    public void initialize() {
        log.info("Initializing DashboardController with live SQLite connection...");

        try (InputStream is = getClass().getResourceAsStream("/com/smartkhata/ui/images/grocery/low_stock_package.png")) {
            if (is != null) {
                lowStockEmptyImage = new Image(is);
            }
        } catch (Exception e) {
            log.debug("Optional low_stock_package icon unavailable: {}", e.getMessage());
        }

        LocalDate now = LocalDate.now();
        activeStartDate = now.withDayOfMonth(1);
        activeEndDate = now.withDayOfMonth(now.lengthOfMonth());

        setupRecentTransactionsTable();
        setupChartProperties();
        updateWelcomeHeader();
        refreshDashboard();
    }

    /**
     * Updates the dashboard date window from the top header date range picker and reloads live metrics.
     */
    public void setDateRange(LocalDate startDate, LocalDate endDate, String periodSubtitle) {
        if (startDate != null && endDate != null) {
            this.activeStartDate = startDate;
            this.activeEndDate = endDate;
        }
        if (periodSubtitle != null && !periodSubtitle.isBlank()) {
            this.activePeriodSubtitle = periodSubtitle.trim();
        }
        updateWelcomeHeader();
        refreshDashboard();
    }

    private void updateWelcomeHeader() {
        Business biz = SessionContext.getCurrentBusiness();
        User user = SessionContext.getCurrentUser();

        String personOrStore = null;
        if (biz != null && biz.name() != null && !biz.name().isBlank()) {
            personOrStore = biz.name().trim();
        } else if (user != null && user.fullName() != null && !user.fullName().isBlank()) {
            personOrStore = user.fullName().trim();
        }

        int hour = LocalTime.now().getHour();
        String timeGreeting = hour < 12 ? "Good Morning" : (hour < 17 ? "Good Afternoon" : "Good Evening");

        if (lblWelcomeGreeting != null) {
            lblWelcomeGreeting.setText(personOrStore != null ? timeGreeting + ", " + personOrStore : timeGreeting);
        }
        if (lblDashboardSubtitle != null) {
            lblDashboardSubtitle.setText("Here's your grocery shop overview " + activePeriodSubtitle + ".");
        }
    }

    private void setupChartProperties() {
        if (revenueTrendChart != null) {
            revenueTrendChart.setLegendVisible(false);
            revenueTrendChart.setAnimated(false);
        }
        if (trendGroup != null) {
            trendGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
                if (newT == null && oldT != null) {
                    oldT.setSelected(true);
                }
            });
        }
    }

    private void setupRecentTransactionsTable() {
        if (recentTransactionsTable == null) return;

        colDate.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().formattedDate()));
        colCustomer.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().partyName()));
        colType.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().type()));
        colAmount.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().amount())));
        colStatus.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().status()));

        colType.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String type, boolean empty) {
                super.updateItem(type, empty);
                if (empty || type == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label pill = new Label(type);
                    if (type.contains("Sale")) {
                        pill.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    } else if (type.contains("Collection")) {
                        pill.setStyle("-fx-background-color: #E0E7FF; -fx-text-fill: #3730A3; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    } else if (type.contains("Supplier") || type.contains("Purchase")) {
                        pill.setStyle("-fx-background-color: #DBEAFE; -fx-text-fill: #1E40AF; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    } else if (type.contains("Expense")) {
                        pill.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #B91C1C; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    } else {
                        pill.setStyle("-fx-background-color: #FEF3C7; -fx-text-fill: #92400E; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    }
                    setGraphic(pill);
                    setText(null);
                }
            }
        });

        colStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label pill = new Label(status);
                    if ("Paid".equalsIgnoreCase(status) || "Received".equalsIgnoreCase(status)) {
                        pill.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    } else if ("Disbursed".equalsIgnoreCase(status)) {
                        pill.setStyle("-fx-background-color: #DBEAFE; -fx-text-fill: #1E40AF; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    } else {
                        pill.setStyle("-fx-background-color: #FFEDD5; -fx-text-fill: #C2410C; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
                    }
                    setGraphic(pill);
                    setText(null);
                }
            }
        });
    }

    private long resolveBusinessId() {
        Business biz = SessionContext.getCurrentBusiness();
        return biz != null && biz.id() != null ? biz.id() : 1L;
    }

    public void refreshDashboard() {
        updateWelcomeHeader();
        loadDashboardData(activeMetric);
    }

    public void loadDashboardData(DashboardService.TrendMetric metric) {
        this.activeMetric = metric;
        long businessId = resolveBusinessId();
        LocalDate start = activeStartDate != null ? activeStartDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = activeEndDate != null ? activeEndDate : LocalDate.now();

        currentLoadFuture = CompletableFuture.runAsync(() -> {
            try {
                DashboardKPIs kpis = dashboardService.getKPIs(businessId, start, end);
                List<SalesTrendPoint> trend = dashboardService.getTrend(businessId, start, end, metric);
                TrendSummaryMetrics summaryMetrics = dashboardService.getTrendSummaryMetrics(businessId, start, end, metric);
                List<RecentTransactionDto> recent = dashboardService.getRecentTransactions(businessId, 10);
                List<LowStockItemDto> lowStock = dashboardService.getLowStockAlerts(businessId, 5);
                OutstandingDuesSummary dues = dashboardService.getOutstandingDues(businessId);

                Platform.runLater(() -> {
                    updateKPIViews(kpis);
                    updateChartView(trend, metric, summaryMetrics);
                    updateRecentTransactions(recent);
                    updateLowStockAlerts(lowStock);
                    updateOutstandingDues(dues);
                });
            } catch (Exception e) {
                log.error("Failed to load dashboard data: {}", e.getMessage(), e);
            }
        }, executor);
    }

    public void shutdown() {
        try {
            CompletableFuture<Void> pending = currentLoadFuture;
            if (pending != null && !pending.isDone()) {
                pending.get(1, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.debug("Dashboard background load termination wait interrupted: {}", e.getMessage());
        }
        executor.shutdownNow();
    }

    private void updateKPIViews(DashboardKPIs kpis) {
        if (totalSalesLabel != null) totalSalesLabel.setText(BengaliMoneyFormatter.format(kpis.totalSales()));
        updateGrowthBadge(salesArrowIcon, salesGrowthLabel, kpis.salesGrowthPercent(), false);

        if (dueCollectionLabel != null) dueCollectionLabel.setText(BengaliMoneyFormatter.format(kpis.dueCollection()));
        updateGrowthBadge(collectionArrowIcon, collectionGrowthLabel, kpis.collectionGrowthPercent(), false);

        if (expensesLabel != null) expensesLabel.setText(BengaliMoneyFormatter.format(kpis.expenses()));
        updateGrowthBadge(expenseArrowIcon, expenseGrowthLabel, kpis.expenseGrowthPercent(), true);

        if (inventoryValueLabel != null) inventoryValueLabel.setText(BengaliMoneyFormatter.format(kpis.inventoryValue()));
        if (stockCountLabel != null) stockCountLabel.setText(kpis.totalStockItemsCount() + " items in stock");
    }

    private void updateGrowthBadge(SVGPath arrow, Label pctLabel, double percent, boolean higherIsRed) {
        boolean isPositiveValue = percent >= 0;
        boolean alertColor = higherIsRed ? (percent > 0) : (percent < 0);
        String colorHex = alertColor ? "#DC2626" : "#15803D";

        if (arrow != null) {
            arrow.setContent(isPositiveValue ? UP_ARROW_PATH : DOWN_ARROW_PATH);
            arrow.setFill(Color.web(colorHex));
        }
        if (pctLabel != null) {
            String sign = isPositiveValue ? "+" : "";
            pctLabel.setText(sign + percent + "%");
            pctLabel.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-weight: bold; -fx-font-size: 10.5px;");
        }
    }

    private void updateChartView(List<SalesTrendPoint> points, DashboardService.TrendMetric metric, TrendSummaryMetrics summary) {
        if (revenueTrendChart == null) return;

        boolean hasData = points != null && !points.isEmpty();

        if (yAxis != null) {
            yAxis.setAutoRanging(hasData);
            if (!hasData) {
                yAxis.setLowerBound(0);
                yAxis.setUpperBound(125);
                yAxis.setTickUnit(25);
            }
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(metric.name());

        if (hasData) {
            for (SalesTrendPoint p : points) {
                series.getData().add(new XYChart.Data<>(p.dateLabel(), p.amount().toBdt()));
            }
        }

        revenueTrendChart.getData().setAll(series);

        if (trendEmptyOverlay != null) {
            trendEmptyOverlay.setVisible(!hasData);
            trendEmptyOverlay.setManaged(!hasData);
        }

        updateTrendTitlesAndEmptyState(metric);

        if (chartTotalSalesLabel != null) chartTotalSalesLabel.setText(BengaliMoneyFormatter.format(summary.totalAmount()));
        if (chartAvgDailySalesLabel != null) chartAvgDailySalesLabel.setText(BengaliMoneyFormatter.format(summary.avgDailyAmount()));
        if (lblChartCountTitle != null) lblChartCountTitle.setText(summary.countLabel());
        if (chartTotalInvoicesLabel != null) chartTotalInvoicesLabel.setText(String.valueOf(summary.count()));
    }

    private void updateTrendTitlesAndEmptyState(DashboardService.TrendMetric metric) {
        switch (metric) {
            case SALES -> {
                if (lblTrendChartTitle != null) lblTrendChartTitle.setText("Sales Trend");
                if (lblTrendChartSubtitle != null) lblTrendChartSubtitle.setText("Daily sales for the selected period.");
                if (lblChartMetricTitle != null) lblChartMetricTitle.setText("Total Sales Volume");
                if (lblEmptyTrendTitle != null) lblEmptyTrendTitle.setText("No sales data yet");
                if (lblEmptyTrendSubtitle != null) lblEmptyTrendSubtitle.setText("Sales will appear here once you record a sale.");
                if (btnEmptyTrendAction != null) btnEmptyTrendAction.setText("+ Add Sale");
            }
            case COLLECTION -> {
                if (lblTrendChartTitle != null) lblTrendChartTitle.setText("Due Collection Trend");
                if (lblTrendChartSubtitle != null) lblTrendChartSubtitle.setText("Daily customer collections for the selected period.");
                if (lblChartMetricTitle != null) lblChartMetricTitle.setText("Total Due Collections");
                if (lblEmptyTrendTitle != null) lblEmptyTrendTitle.setText("No collections yet");
                if (lblEmptyTrendSubtitle != null) lblEmptyTrendSubtitle.setText("Collections will appear here once you receive a payment.");
                if (btnEmptyTrendAction != null) btnEmptyTrendAction.setText("+ Receive Payment");
            }
            case EXPENSES -> {
                if (lblTrendChartTitle != null) lblTrendChartTitle.setText("Operating Expenses Trend");
                if (lblTrendChartSubtitle != null) lblTrendChartSubtitle.setText("Daily overhead expenses for the selected period.");
                if (lblChartMetricTitle != null) lblChartMetricTitle.setText("Total Expenses");
                if (lblEmptyTrendTitle != null) lblEmptyTrendTitle.setText("No expenses recorded yet");
                if (lblEmptyTrendSubtitle != null) lblEmptyTrendSubtitle.setText("Expenses will appear here once you record an expense.");
                if (btnEmptyTrendAction != null) btnEmptyTrendAction.setText("+ Add Expense");
            }
        }
    }

    private void updateRecentTransactions(List<RecentTransactionDto> list) {
        if (recentTransactionsTable != null) {
            recentTransactionsTable.setItems(FXCollections.observableArrayList(list));
        }
    }

    private void updateLowStockAlerts(List<LowStockItemDto> items) {
        if (lowStockBadgeLabel != null) {
            int count = items.size();
            lowStockBadgeLabel.setText(count + (count == 1 ? " item" : " items"));
            lowStockBadgeLabel.setStyle(items.isEmpty()
                    ? "-fx-background-color: #FFF1E6; -fx-text-fill: #C2410C; -fx-padding: 2 9; -fx-background-radius: 10; -fx-font-weight: bold; -fx-font-size: 11px;"
                    : "-fx-background-color: #FEE2E2; -fx-text-fill: #B91C1C; -fx-padding: 2 9; -fx-background-radius: 10; -fx-font-weight: bold; -fx-font-size: 11px;");
        }
        if (lowStockListContainer != null) {
            lowStockListContainer.getChildren().clear();
            if (items.isEmpty()) {
                HBox emptyBox = new HBox(12);
                emptyBox.setAlignment(Pos.CENTER_LEFT);
                Label emptyLbl = new Label("All inventory items are adequately stocked.");
                emptyLbl.setWrapText(true);
                emptyLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748B;");
                HBox.setHgrow(emptyLbl, Priority.ALWAYS);
                emptyBox.getChildren().add(emptyLbl);

                if (lowStockEmptyImage != null) {
                    ImageView iv = new ImageView(lowStockEmptyImage);
                    iv.setFitWidth(56);
                    iv.setFitHeight(56);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    emptyBox.getChildren().add(iv);
                }
                lowStockListContainer.getChildren().add(emptyBox);
                return;
            }

            for (LowStockItemDto item : items) {
                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setStyle("-fx-cursor: hand; -fx-padding: 5 8; -fx-background-radius: 7;");
                row.setOnMouseEntered(e -> row.setStyle("-fx-cursor: hand; -fx-padding: 5 8; -fx-background-radius: 7; -fx-background-color: #F0FDF4;"));
                row.setOnMouseExited(e -> row.setStyle("-fx-cursor: hand; -fx-padding: 5 8; -fx-background-radius: 7; -fx-background-color: transparent;"));

                row.setOnMouseClicked(e -> {
                    e.consume();
                    log.info("Low stock alert clicked for product [{}] - navigating to Inventory", item.productName());
                    ViewRouter.navigateTo(ViewRouter.ViewId.INVENTORY);
                });

                Circle dot = new Circle(3.5, Color.web("#EA580C"));

                Label name = new Label(item.productName());
                name.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 600; -fx-text-fill: #334155;");
                HBox.setHgrow(name, Priority.ALWAYS);

                Label qty = new Label(item.currentStock() + " " + item.unit());
                qty.setStyle("-fx-text-fill: #DC2626; -fx-font-weight: bold; -fx-font-size: 12px;");

                row.getChildren().addAll(dot, name, qty);
                lowStockListContainer.getChildren().add(row);
            }
        }
    }

    private void updateOutstandingDues(OutstandingDuesSummary dues) {
        if (totalBakiLabel != null) {
            totalBakiLabel.setText(BengaliMoneyFormatter.format(dues.totalDues()));
        }
        if (bakiCustomerCountLabel != null) {
            int count = dues.customersWithDueCount();
            bakiCustomerCountLabel.setText(count + (count == 1 ? " customer with positive due" : " customers with positive due"));
        }
    }

    @FXML
    private void onToggleSalesTrend() {
        loadDashboardData(DashboardService.TrendMetric.SALES);
    }

    @FXML
    private void onToggleCollectionTrend() {
        loadDashboardData(DashboardService.TrendMetric.COLLECTION);
    }

    @FXML
    private void onToggleExpensesTrend() {
        loadDashboardData(DashboardService.TrendMetric.EXPENSES);
    }

    @FXML
    private void onEmptyTrendAction() {
        switch (activeMetric) {
            case SALES -> ViewRouter.navigateTo(ViewRouter.ViewId.SALES);
            case COLLECTION -> ViewRouter.navigateTo(ViewRouter.ViewId.PAYMENTS);
            case EXPENSES -> ViewRouter.navigateTo(ViewRouter.ViewId.EXPENSES);
        }
    }

    @FXML
    private void onViewAllTransactions() {
        ViewRouter.navigateTo(ViewRouter.ViewId.SALES);
    }

    @FXML
    private void onNavigateToDues() {
        ViewRouter.navigateTo(ViewRouter.ViewId.CUSTOMERS);
    }

    @FXML
    private void onNavigateToInventory() {
        ViewRouter.navigateTo(ViewRouter.ViewId.INVENTORY);
    }

    @FXML
    private void onQuickAddSale() {
        log.info("Dashboard Quick Action -> New Sale");
        ViewRouter.navigateTo(ViewRouter.ViewId.SALES);
    }

    @FXML
    private void onQuickReceivePayment() {
        log.info("Dashboard Quick Action -> Receive Payment");
        ViewRouter.navigateTo(ViewRouter.ViewId.PAYMENTS);
    }

    @FXML
    private void onQuickAddExpense() {
        log.info("Dashboard Quick Action -> Add Expense");
        ViewRouter.navigateTo(ViewRouter.ViewId.EXPENSES);
    }

    @FXML
    private void onQuickAddCustomer() {
        log.info("Dashboard Quick Action -> Add Customer");
        ViewRouter.navigateTo(ViewRouter.ViewId.CUSTOMERS);
    }
}
