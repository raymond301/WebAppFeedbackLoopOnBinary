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

// --- 1. FILE SELECTION ---
def file = qupath.lib.gui.dialogs.Dialogs.promptForFile("Select BACKGROUNDTILE file", null, "Text files", ".txt", ".tsv", ".csv")
if (!file) return

def lines = file.readLines()
def header = lines[0].split("\t").toList()
def sortedHeader = header.sort(false)

// --- 2. MATH CONFIGURATION ---
def params = new ParameterList()
params.addChoiceParameter("num", "Numerator", sortedHeader[0], sortedHeader)
params.addChoiceParameter("den", "Denominator", sortedHeader[0], sortedHeader)
params.addChoiceParameter("op", "Operation", "ratio", ["delta", "ratio"])
params.addChoiceParameter("trans", "Transformation", "none", ["none", "log2"])

if (!qupath.lib.gui.dialogs.Dialogs.showParameterDialog("SNR Math Configuration", params)) return

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
    qupath.lib.gui.dialogs.Dialogs.showErrorMessage("Error", "CellObjectID column missing.")
    return
}

int numIdx = header.indexOf(numCol)
int denIdx = header.indexOf(denCol)
def dataMap = [:]
def resultsList = []

lines.drop(1).each { line ->
    def parts = line.split("\t")
    try {
        double n = parts[numIdx].toDouble()
        double d = parts[denIdx].toDouble()
        double val = (op == "delta") ? (n - d) : (n / d)
        if (trans == "log2") val = (val > 0) ? Math.log(val) / Math.log(2) : 0
        dataMap[parts[idIdx]] = val
        resultsList << val
    } catch (Exception e) {}
}

def hierarchy = getCurrentImageData().getHierarchy()
def detections = hierarchy.getDetectionObjects()

detections.each { cell ->
    def id = cell.getID().toString()
    if (dataMap.containsKey(id)) {
        double val = dataMap[id]
        cell.measurements.put("Calculated_SNR", val)
    }
}
fireHierarchyUpdate()

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
    
    // PREVIEW ACTION
    applyBtn.setOnAction { e ->
        double threshold = Double.parseDouble(tInput.getText())
        detections.each { cell ->
            // Accessing via MeasurementList to fix MissingMethodException
            def val = cell.getMeasurementList().getMeasurementValue("Calculated_SNR") ?: 0.0
            cell.setPathClass(val >= threshold ? classPositive : classNegative)
        }
        Platform.runLater { fireHierarchyUpdate() }
    }

    // KEEP ACTION
    keepBtn.setOnAction { e ->
        double threshold = Double.parseDouble(tInput.getText())
        detections.each { cell ->
            def val = cell.getMeasurementList().getMeasurementValue("Calculated_SNR") ?: 0.0
            cell.setPathClass(val >= threshold ? classPositive : classNegative)
            cell.measurements.put("SNR_Keep_Pass", (val >= threshold ? 1.0 : 0.0))
        }
        println "--- SNR DECISION SAVED ---"
        println "Threshold: ${threshold}"
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