import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.chart.BarChart
import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.stage.Stage
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.objects.classes.PathClass

def dialogs = qupath.lib.gui.dialogs.Dialogs

def splitLine = { String line, String delimiter ->
    // Keep trailing empty fields so column indexing remains stable.
    return line.split(java.util.regex.Pattern.quote(delimiter), -1)
}

def detectDelimiter = { String line ->
    if (line == null) return "\t"
    if (line.contains("\t")) return "\t"
    if (line.contains(",")) return ","
    if (line.contains(";")) return ";"
    return "\t"
}

def readMeasurementValue = { obj, String name ->
    def ml = obj?.getMeasurementList()
    if (ml == null) return Double.NaN
    try {
        return ml.get(name)
    } catch (Exception ignored) {
        try {
            return ml.getMeasurementValue(name)
        } catch (Exception ignored2) {
            return Double.NaN
        }
    }
}

def writeMeasurementValue = { obj, String name, double value ->
    def ml = obj?.getMeasurementList()
    if (ml == null) return false
    try {
        ml.put(name, value)
        return true
    } catch (Exception ignored) {
        try {
            ml.putMeasurement(name, value)
            return true
        } catch (Exception ignored2) {
            return false
        }
    }
}

// --- 1. FILE SELECTION ---
def file = dialogs.promptForFile("Select BACKGROUNDTILE file", null, "Text files", ".txt", ".tsv", ".csv")
if (!file) return

def lines = file.readLines()
if (!lines || lines.isEmpty()) {
    dialogs.showErrorMessage("Error", "Selected file is empty.")
    return
}

def delimiter = detectDelimiter(lines[0])
def header = splitLine(lines[0], delimiter).toList()
if (!header || header.isEmpty()) {
    dialogs.showErrorMessage("Error", "Header row is missing or could not be parsed.")
    return
}

def sortedHeader = header.sort(false)

// --- 2. MATH CONFIGURATION ---
def params = new ParameterList()
params.addChoiceParameter("num", "Numerator", sortedHeader[0], sortedHeader)
params.addChoiceParameter("den", "Denominator", sortedHeader[0], sortedHeader)
params.addChoiceParameter("op", "Operation", "ratio", ["delta", "ratio"])
params.addChoiceParameter("trans", "Transformation", "none", ["none", "log2"])

if (!dialogs.showParameterDialog("SNR Math Configuration", params)) return

def numCol = params.getChoiceParameterValue("num")
def denCol = params.getChoiceParameterValue("den")
def op = params.getChoiceParameterValue("op")
def trans = params.getChoiceParameterValue("trans")

// --- 3. COMPUTATION & CLASS SETUP ---
// Using modern PathClass assignment
def classPositive = PathClass.fromString("(+)")
def classNegative = PathClass.fromString("(-)")

int idIdx = -1
header.eachWithIndex { col, i -> if (col.equalsIgnoreCase("CellObjectID")) idIdx = i }
if (idIdx == -1) {
    dialogs.showErrorMessage("Error", "CellObjectID column missing.")
    return
}

int numIdx = header.indexOf(numCol)
int denIdx = header.indexOf(denCol)
if (numIdx < 0 || denIdx < 0) {
    dialogs.showErrorMessage("Error", "Selected numerator/denominator columns were not found in the file.")
    return
}

def dataMap = [:]
def resultsList = []
int parsedRows = 0
int skippedRows = 0
int zeroDenominatorRows = 0

lines.drop(1).each { line ->
    if (line == null || line.trim().isEmpty()) {
        skippedRows++
        return
    }
    def parts = splitLine(line, delimiter)
    if (parts.size() <= [idIdx, numIdx, denIdx].max()) {
        skippedRows++
        return
    }
    try {
        def cellId = parts[idIdx]?.trim()
        if (!cellId) {
            skippedRows++
            return
        }
        double n = parts[numIdx].toDouble()
        double d = parts[denIdx].toDouble()
        if (op == "ratio" && d == 0d) {
            zeroDenominatorRows++
            skippedRows++
            return
        }
        double val = (op == "delta") ? (n - d) : (n / d)
        if (trans == "log2") {
            if (val <= 0d) {
                skippedRows++
                return
            }
            val = Math.log(val) / Math.log(2)
        }
        dataMap[cellId] = val
        resultsList << val
        parsedRows++
    } catch (Exception e) {
        skippedRows++
    }
}

if (resultsList.isEmpty()) {
    dialogs.showErrorMessage("Error", "No valid numeric rows were parsed. Check delimiter, selected columns, and numeric values.")
    return
}

def hierarchy = getCurrentImageData().getHierarchy()
def detections = hierarchy.getDetectionObjects()
if (!detections || detections.isEmpty()) {
    dialogs.showErrorMessage("Error", "No detection objects found in the current image.")
    return
}

int measurementWrites = 0

detections.each { cell ->
    def id = cell.getID().toString()
    if (dataMap.containsKey(id)) {
        double val = dataMap[id]
        if (writeMeasurementValue(cell, "Calculated_SNR", val)) {
            measurementWrites++
        }
    }
}
fireHierarchyUpdate()

if (measurementWrites == 0) {
    dialogs.showWarningNotification("SNR", "No matching CellObjectID values were found between file and detections.")
}

// --- 4. CALCULATE MODE ---
def bins = resultsList.groupBy { Math.round(it * 10) / 10.0 }
def modeValue = bins.max { it.value.size() }?.key ?: 0

// --- 5. INTERACTIVE JAVAFX DASHBOARD ---
Platform.runLater {
    Stage stage = new Stage()
    stage.setTitle("SNR Class Validation Dashboard")

    VBox root = new VBox(15)
    root.setPadding(new javafx.geometry.Insets(20))

    CategoryAxis xAxis = new CategoryAxis()
    NumberAxis yAxis = new NumberAxis()
    BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis)
    chart.setAnimated(false)
    chart.setLegendVisible(false)
    chart.setTitle("Distribution (Mode: ${modeValue})")
    
    XYChart.Series series = new XYChart.Series()
    bins.sort().each { k, v -> series.getData().add(new XYChart.Data(k.toString(), v.size())) }
    chart.getData().add(series)

    TextField tInput = new TextField("${modeValue}")
    Button applyBtn = new Button("Preview Classes")
    Button keepBtn = new Button("KEEP (Save Decision)")
    keepBtn.setStyle("-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-weight: bold;")

    def parseThreshold = {
        try {
            return Double.parseDouble(tInput.getText().trim())
        } catch (Exception ignored) {
            dialogs.showErrorMessage("Invalid Threshold", "Enter a valid numeric threshold.")
            return null
        }
    }
    
    // PREVIEW ACTION
    applyBtn.setOnAction { e ->
        def threshold = parseThreshold()
        if (threshold == null) return
        int updated = 0
        detections.each { cell ->
            double val = readMeasurementValue(cell, "Calculated_SNR")
            if (!Double.isNaN(val)) {
                cell.setPathClass(val >= threshold ? classPositive : classNegative)
                updated++
            }
        }
        fireHierarchyUpdate()
        dialogs.showInfoNotification("SNR Preview", "Updated classes for ${updated} detections.")
    }

    // KEEP ACTION
    keepBtn.setOnAction { e ->
        def threshold = parseThreshold()
        if (threshold == null) return
        int updated = 0
        int saved = 0
        detections.each { cell ->
            double val = readMeasurementValue(cell, "Calculated_SNR")
            if (!Double.isNaN(val)) {
                boolean isPositive = val >= threshold
                cell.setPathClass(isPositive ? classPositive : classNegative)
                if (writeMeasurementValue(cell, "SNR_Keep_Pass", (isPositive ? 1.0d : 0.0d))) {
                    saved++
                }
                updated++
            }
        }
        println "--- SNR DECISION SAVED ---"
        println "Threshold: ${threshold}"
        println "Rows parsed: ${parsedRows}; rows skipped: ${skippedRows}; ratio divide-by-zero rows skipped: ${zeroDenominatorRows}"
        println "Detections classified: ${updated}; SNR_Keep_Pass saved: ${saved}"
        Platform.runLater { 
            fireHierarchyUpdate()
            stage.close() 
        }
    }

    HBox controls = new HBox(10, tInput, applyBtn, keepBtn)
    root.getChildren().addAll(new Label("Equation: ${trans}(${numCol} ${op} ${denCol})"), chart, new Label("Adjust Threshold:"), controls)
    
    stage.setScene(new Scene(root, 800, 650))
    stage.show()
}