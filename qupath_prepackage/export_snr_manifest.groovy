import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.stage.Stage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import qupath.lib.regions.RegionRequest
import qupath.lib.images.servers.TransformedServerBuilder
import qupath.lib.images.writers.ImageWriterTools
import qupath.lib.objects.classes.PathClass
import static qupath.lib.scripting.QP.*

// --- 1. CONFIGURATION ---
def cropSizeMicrons = 200.0 
def maxSpotsToVisit = 20    
def outputDirName = "Exported_Packets"

def project = getProject()
if (project == null) {
    qupath.lib.gui.dialogs.Dialogs.showErrorMessage("Project Error", "Please save your project first.")
    return
}

def imageData = getCurrentImageData()
def server = imageData.getServer()
def viewer = getCurrentViewer()

// QuPath channel APIs differ across versions; resolve channels dynamically.
def imageDisplay = viewer.getImageDisplay()

def channelInfos = []
if (imageDisplay.metaClass.respondsTo(imageDisplay, "availableChannels")) {
    channelInfos = imageDisplay.availableChannels()
} else if (imageDisplay.metaClass.respondsTo(imageDisplay, "getAvailableChannels")) {
    channelInfos = imageDisplay.getAvailableChannels()
} else if (imageDisplay.metaClass.respondsTo(imageDisplay, "channels")) {
    channelInfos = imageDisplay.channels()
} else if (imageDisplay.metaClass.respondsTo(imageDisplay, "getChannels")) {
    channelInfos = imageDisplay.getChannels()
}

def selectedChannelSet = [] as Set
if (imageDisplay.metaClass.respondsTo(imageDisplay, "selectedChannels")) {
    selectedChannelSet.addAll(imageDisplay.selectedChannels() ?: [])
} else if (imageDisplay.metaClass.respondsTo(imageDisplay, "getSelectedChannels")) {
    selectedChannelSet.addAll(imageDisplay.getSelectedChannels() ?: [])
}

if (channelInfos == null || channelInfos.isEmpty()) {
    qupath.lib.gui.dialogs.Dialogs.showErrorMessage("Channel Error", "Could not read channel metadata from ImageDisplay for this QuPath version.")
    return
}

def activeChannelIndices = []
def activeChannelNames = []

def readMeasurementValue = { measurementList, measurementName ->
    if (measurementList == null) return 0.0
    def value = null
    if (measurementList.metaClass.respondsTo(measurementList, "get", measurementName)) {
        value = measurementList.get(measurementName)
    } else if (measurementList.metaClass.respondsTo(measurementList, "getMeasurementValue", measurementName)) {
        value = measurementList.getMeasurementValue(measurementName)
    }
    return (value instanceof Number) ? (value as double) : 0.0
}

channelInfos.eachWithIndex { info, i ->
    def isActive = !selectedChannelSet.isEmpty() ? selectedChannelSet.contains(info) :
        (info.metaClass.respondsTo(info, "isVisible") ? info.isVisible() : true)
    if (isActive) {
        activeChannelIndices << i
        activeChannelNames << info.getName()
    }
}

if (activeChannelIndices.isEmpty()) {
    qupath.lib.gui.dialogs.Dialogs.showErrorMessage("Channel Error", "No channels are active. Please toggle at least one marker on.")
    return
}

// Marker naming logic for the folder structure
def markerName = activeChannelNames.find { !it.toUpperCase().contains("DAPI") } ?: "Combined"
def projectPath = project.getPath().getParent().toString()
def imageName = GeneralTools.stripExtension(server.getMetadata().getName())
def exportFolder = buildFilePath(projectPath, outputDirName, imageName, markerName)
mkdirs(exportFolder)

// --- 2. SPOT IDENTIFICATION (Density Clustering) ---
def hierarchy = imageData.getHierarchy()
def posClass = PathClass.fromString("(+)")
def positiveCells = hierarchy.getDetectionObjects().findAll { it.getPathClass() == posClass }

if (positiveCells.isEmpty()) {
    qupath.lib.gui.dialogs.Dialogs.showErrorMessage("Data Error", "No (+) cells found. Please run your SNR thresholding script first.")
    return
}

def pixelSize = server.getPixelCalibration().getAveragedPixelSize()
def gridDim = (int)(cropSizeMicrons / pixelSize)
def grid = positiveCells.groupBy { 
    [ (int)(it.getROI().getCentroidX() / gridDim), (int)(it.getROI().getCentroidY() / gridDim) ] 
}

def sortedSpots = grid.entrySet()
    .sort { -it.value.size() }
    .take(maxSpotsToVisit)
    .collect { entry ->
        def coords = entry.key
        return [x: (coords[0] + 0.5) * gridDim, y: (coords[1] + 0.5) * gridDim, count: entry.value.size()]
    }

if (sortedSpots.isEmpty()) {
    qupath.lib.gui.dialogs.Dialogs.showErrorMessage("Data Error", "No hotspot candidates were identified from (+) cells.")
    return
}

// --- 3. INTERACTIVE CONTROLLER ---
Platform.runLater {
    Stage stage = new Stage()
    stage.setTitle("Manifest Export Controller")
    int currentIndex = 0
    int savedCount = 0

    VBox root = new VBox(12)
    root.setPadding(new javafx.geometry.Insets(20))
    Label infoLabel = new Label("Reviewing Spot 1 of ${sortedSpots.size()}")
    Label chanLabel = new Label("Active: " + activeChannelNames.join(", "))
    chanLabel.setWrapText(true)
    Label statLabel = new Label("Packets Exported: 0")
    statLabel.setStyle("-fx-font-weight: bold;")
    def tsFormatter = DateTimeFormatter.ofPattern("HHmmss")

    def centerViewerAt = { x, y ->
        double cx = x as double
        double cy = y as double
        if (viewer.metaClass.respondsTo(viewer, "centerROI", qupath.lib.roi.interfaces.ROI)) {
            def pointROI = ROIs.createRectangleROI(cx, cy, 1, 1, null)
            viewer.centerROI(pointROI)
        } else {
            qupath.lib.gui.dialogs.Dialogs.showErrorMessage("Viewer Error", "Could not center viewer for this QuPath version.")
        }
    }

    def moveViewer = { index ->
        def spot = sortedSpots[index]
        centerViewerAt(spot.x, spot.y)
        infoLabel.setText("Reviewing Spot ${index + 1} of ${sortedSpots.size()} (${spot.count} cells)")
    }

    moveViewer(0)

    Button keepBtn = new Button("KEEP & EXPORT")
    keepBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 120;")
    
    Button nextBtn = new Button("NEXT (Skip)")
    nextBtn.setStyle("-fx-min-width: 100;")
    
    keepBtn.setOnAction { e ->
        def spot = sortedSpots[currentIndex]
        def timestamp = LocalDateTime.now().format(tsFormatter)
        def packetPath = buildFilePath(exportFolder, "Packet_${savedCount + 1}_${timestamp}")
        mkdirs(packetPath)

        // 0. Packet-level metadata for downstream indexing & provenance
        def packetInfoFile = new File(buildFilePath(packetPath, "packet_info.json"))
        def channels = activeChannelNames.collect { '"' + it + '"' }.join(', ')
        def jsonContent = """{
  "packetIndex": ${savedCount + 1},
  "timestamp": "${timestamp}",
  "imageName": "${imageName}",
  "markerName": "${markerName}",
  "cropSizeMicrons": ${cropSizeMicrons},
  "pixelSizeMicrons": ${pixelSize},
  "cropSizePixels": ${gridDim},
  "spotCenterX": ${spot.x},
  "spotCenterY": ${spot.y},
  "spotCellCount": ${spot.count},
  "activeChannels": [${channels}]
}"""
        packetInfoFile.text = jsonContent

        // 1. Filter server to active channels ONLY
        def filteredServer = new TransformedServerBuilder(server)
            .extractChannels(activeChannelIndices as int[])
            .build()

        // 2. Export OME-TIFF Crop
        def region = RegionRequest.createInstance(filteredServer.getPath(), 1.0, 
            (int)(spot.x - gridDim/2), (int)(spot.y - gridDim/2), gridDim, gridDim)
        
        def outPath = buildFilePath(packetPath, "crop.ome.tif")
        ImageWriterTools.writeImageRegion(filteredServer, region, outPath)

        // 3. Serializing the Manifest CSV
        def cropROI = ROIs.createRectangleROI(spot.x - gridDim/2, spot.y - gridDim/2, gridDim, gridDim, null)
        def localCells = positiveCells.findAll { cropROI.contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) }
        
        def metadataFile = new File(buildFilePath(packetPath, "manifest.csv"))
        metadataFile.withWriter { writer ->
            writer.writeLine("CellID,X,Y,SNR,Class,ChannelsUsed")
            localCells.each { cell ->
                def snr = readMeasurementValue(cell.getMeasurementList(), 'Calculated_SNR')
                writer.writeLine("${cell.getID()},${cell.getROI().getCentroidX()},${cell.getROI().getCentroidY()},${snr},${cell.getPathClass()},${activeChannelNames.join('|')}")
            }
        }

        savedCount++
        statLabel.setText("Packets Exported: ${savedCount}")
        println "Exported packet ${savedCount} to ${packetPath}"

        if (currentIndex < sortedSpots.size() - 1) {
            currentIndex++
            moveViewer(currentIndex)
        } else {
            println "Finished reviewing all spots."
            stage.close()
        }
    }

    nextBtn.setOnAction { e ->
        if (currentIndex < sortedSpots.size() - 1) {
            currentIndex++
            moveViewer(currentIndex)
        } else {
            stage.close()
        }
    }

    HBox controls = new HBox(15, keepBtn, nextBtn)
    root.getChildren().addAll(infoLabel, chanLabel, statLabel, controls)
    stage.setScene(new Scene(root, 450, 250))
    stage.setAlwaysOnTop(true)
    stage.show()
}