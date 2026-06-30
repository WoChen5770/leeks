package handler;

import bean.StockBean;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.JBColor;
import com.intellij.ui.table.JBTable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import utils.PinYinUtils;
import utils.WindowUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.*;

public abstract class StockRefreshHandler extends DefaultTableModel {
    private String[] columnNames = new String[0];
    /**
     * 存放【编码】的位置，更新数据时用到
     */
    public int codeColumnIndex;

    private JTable table;
    private boolean colorful = true;
    private boolean showReturn = true;
    static JLabel refreshTimeLabel;

    private static final Set<String> STOCK_RETURN_COLUMNS = new HashSet<>(Arrays.asList("成本价", "持仓", "收益率", "收益"));

    public StockRefreshHandler(JTable table, JLabel refreshTimeLabel) {
        this.table = table;
        StockRefreshHandler.refreshTimeLabel = refreshTimeLabel;
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        // Fix tree row height
        FontMetrics metrics = table.getFontMetrics(table.getFont());
        table.setRowHeight(Math.max(table.getRowHeight(), metrics.getHeight()));
        table.setModel(this);
        reloadColumnNames();
        refreshColorful(!colorful);
    }

    public void reloadColumnNames() {
        PropertiesComponent instance = PropertiesComponent.getInstance();
        String tableHeaderValue = instance.getValue(WindowUtils.STOCK_TABLE_HEADER_KEY);
        if (StringUtils.isBlank(tableHeaderValue)) {
            tableHeaderValue = WindowUtils.STOCK_TABLE_HEADER_VALUE;
        }
        String migratedTableHeaderValue = migrateTableHeader(tableHeaderValue);
        if (!StringUtils.equals(tableHeaderValue, migratedTableHeaderValue)) {
            instance.setValue(WindowUtils.STOCK_TABLE_HEADER_KEY, migratedTableHeaderValue);
            tableHeaderValue = migratedTableHeaderValue;
        } else if (StringUtils.isBlank(instance.getValue(WindowUtils.STOCK_TABLE_HEADER_KEY))) {
            instance.setValue(WindowUtils.STOCK_TABLE_HEADER_KEY, migratedTableHeaderValue);
            tableHeaderValue = migratedTableHeaderValue;
        }

        String[] configStr = tableHeaderValue.split(",");
        columnNames = new String[configStr.length];
        for (int i = 0; i < configStr.length; i++) {
            columnNames[i] = WindowUtils.remapPinYin(configStr[i]);
        }
        refreshCodeColumnIndex();
        refreshColorful(colorful, showReturn, true);
    }

    private String migrateTableHeader(String tableHeaderValue) {
        List<String> columns = new ArrayList<>();
        for (String column : tableHeaderValue.split(",")) {
            if (StringUtils.isBlank(column)) {
                continue;
            }
            columns.add(WindowUtils.remapPinYin(column));
        }
        if (columns.isEmpty()) {
            columns.addAll(Arrays.asList(WindowUtils.STOCK_TABLE_HEADER_VALUE.split(",")));
        }
        if (!columns.contains("封单量")) {
            int insertIndex = columns.indexOf("成本价");
            if (insertIndex < 0) {
                insertIndex = columns.indexOf("更新时间");
            }
            if (insertIndex < 0) {
                insertIndex = columns.size();
            }
            columns.add(insertIndex, "封单量");
        }
        return String.join(",", columns);
    }

    private void refreshCodeColumnIndex() {
        codeColumnIndex = 0;
        for (int i = 0; i < columnNames.length; i++) {
            if ("编码".equals(columnNames[i])) {
                codeColumnIndex = i;
                return;
            }
        }
    }

    private String[] getDisplayColumnNames() {
        if (showReturn) {
            return columnNames;
        }
        String[] filtered = Arrays.stream(columnNames)
                .filter(name -> !STOCK_RETURN_COLUMNS.contains(name))
                .toArray(String[]::new);
        return filtered;
    }

    public void refreshColorful(boolean colorful) {
        refreshColorful(colorful, showReturn, false);
    }

    private void refreshColorful(boolean colorful, boolean showReturn, boolean forceRefresh) {
        boolean showReturnChanged = this.showReturn != showReturn;
        this.showReturn = showReturn;
        String[] displayColumns = getDisplayColumnNames();
        if (!forceRefresh && this.colorful == colorful && !showReturnChanged) {
            return;
        }
        this.colorful = colorful;
        // 刷新表头
        if (colorful) {
            setColumnIdentifiers(displayColumns);
        } else {
            setColumnIdentifiers(PinYinUtils.toPinYin(displayColumns));
        }
        TableRowSorter<DefaultTableModel> rowSorter = new TableRowSorter<>(this);
        Comparator<Object> doubleComparator = (o1, o2) -> {
            Double v1 = NumberUtils.toDouble(StringUtils.remove((String) o1, '%'));
            Double v2 = NumberUtils.toDouble(StringUtils.remove((String) o2, '%'));
            return v1.compareTo(v2);
        };
        Arrays.stream("当前价,涨跌,涨跌幅,最高价,最低价,封单量".split(",")).map(name -> WindowUtils.getColumnIndexByName(displayColumns, name))
                .filter(index -> index >= 0).forEach(index -> rowSorter.setComparator(index, doubleComparator));
        table.setRowSorter(rowSorter);
        columnColors(colorful);
    }

    public void setShowReturn(boolean showReturn) {
        refreshColorful(colorful, showReturn, false);
    }

    /**
     * 从网络更新数据
     *
     * @param code
     */
    public abstract void handle(List<String> code);

    /**
     * 设置表格条纹（斑马线）<br>
     *
     * @param striped true设置条纹
     * @throws RuntimeException 如果table不是{@link JBTable}类型，请自行实现setStriped
     */
    public void setStriped(boolean striped) {
        if (table instanceof JBTable) {
            ((JBTable) table).setStriped(striped);
        } else {
            throw new RuntimeException("table不是JBTable类型，请自行实现setStriped");
        }
    }

    public void setupTable(List<String> code) {
        for (String s : code) {
            updateData(new StockBean(s));
        }
    }

    /**
     * 停止从网络更新数据
     */
    public abstract void stopHandle();

    private void columnColors(boolean colorful) {
        DefaultTableCellRenderer cellRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                double temp = NumberUtils.toDouble(StringUtils.remove(Objects.toString(value), "%"));
                if (temp > 0) {
                    if (colorful) {
                        setForeground(JBColor.RED);
                    } else {
                        setForeground(JBColor.DARK_GRAY);
                    }
                } else if (temp < 0) {
                    if (colorful) {
                        setForeground(JBColor.GREEN);
                    } else {
                        setForeground(JBColor.GRAY);
                    }
                } else {
                    Color orgin = getForeground();
                    setForeground(orgin);
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        };
        String[] displayColumns = getDisplayColumnNames();
        int columnIndex1 = WindowUtils.getColumnIndexByName(displayColumns, "涨跌");
        int columnIndex2 = WindowUtils.getColumnIndexByName(displayColumns, "涨跌幅");

        int columnIndex3 = WindowUtils.getColumnIndexByName(displayColumns, "收益率");
        int columnIndex4 = WindowUtils.getColumnIndexByName(displayColumns, "收益");

        if (columnIndex1 >= 0) table.getColumn(getColumnName(columnIndex1)).setCellRenderer(cellRenderer);
        if (columnIndex2 >= 0) table.getColumn(getColumnName(columnIndex2)).setCellRenderer(cellRenderer);

        if (columnIndex3 >= 0) table.getColumn(getColumnName(columnIndex3)).setCellRenderer(cellRenderer);
        if (columnIndex4 >= 0) table.getColumn(getColumnName(columnIndex4)).setCellRenderer(cellRenderer);
    }

    private static void updateUI() {
        SwingUtilities.invokeLater(() -> {
            refreshTimeLabel.setText(LocalDateTime.now().format(TianTianFundHandler.timeFormatter));
            refreshTimeLabel.setToolTipText("最后刷新时间");
        });
    }

    protected void updateData(StockBean bean) {
        if (bean.getCode() == null) {
            return;
        }
        Vector<Object> convertData = convertData(bean);
        if (convertData == null) {
            return;
        }
        // 获取行
        int index = findRowIndex(codeColumnIndex, bean.getCode());
        if (index >= 0) {
            updateRow(index, convertData);
        } else {
            addRow(convertData);
        }
        updateUI();
    }
    /**
     * 参考源码{@link DefaultTableModel#setValueAt}，此为直接更新行，提高点效率
     *
     * @param rowIndex
     * @param rowData
     */
    protected void updateRow(int rowIndex, Vector<Object> rowData) {
        dataVector.set(rowIndex, rowData);
        // 通知listeners刷新ui
        fireTableRowsUpdated(rowIndex, rowIndex);
    }

    /**
     * 参考源码{@link DefaultTableModel#removeRow(int)}，此为直接清除全部行，提高点效率
     */
    public void clearRow() {
        int size = dataVector.size();
        if (0 < size) {
            dataVector.clear();
            // 通知listeners刷新ui
            fireTableRowsDeleted(0, size - 1);
        }
    }

    /**
     * 查找列项中的valueName所在的行
     *
     * @param columnIndex 列号
     * @param value       值
     * @return 如果不存在返回-1
     */
    protected int findRowIndex(int columnIndex, String value) {
        int rowCount = getRowCount();
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            String valueAt = getValueAt(rowIndex, columnIndex).toString();
            if (StringUtils.equalsIgnoreCase(value.substring(value.indexOf("_") + 1), valueAt)) {
                return rowIndex;
            }
        }
        return -1;
    }

    private Vector<Object> convertData(StockBean stockBean) {
        if (stockBean == null) {
            return null;
        }
        // 与columnNames中的元素保持一致
        String[] displayColumns = getDisplayColumnNames();
        Vector<Object> v = new Vector<Object>(displayColumns.length);
        for (int i = 0; i < displayColumns.length; i++) {
            v.addElement(stockBean.getValueByColumn(displayColumns[i], colorful));
        }
        return v;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }
}
