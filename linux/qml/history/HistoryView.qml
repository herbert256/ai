import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property var reports: []
    property string searchText: ""
    property var selectedReport: null
    property bool showDeleteAllDialog: false
    property bool showDeleteOneDialog: false
    property string pendingDeleteId: ""

    Component.onCompleted: refreshReports()

    function refreshReports() {
        reports = viewModel.getReportHistory()
    }

    function filteredReports() {
        if (searchText.length === 0) return reports
        var lower = searchText.toLowerCase()
        var result = []
        for (var i = 0; i < reports.length; i++) {
            var r = reports[i]
            if ((r.title || "").toLowerCase().indexOf(lower) >= 0 ||
                (r.prompt || "").toLowerCase().indexOf(lower) >= 0) {
                result.push(r)
            }
        }
        return result
    }

    function formatRelativeTime(timestamp) {
        if (!timestamp) return ""
        var now = Date.now()
        var ts = typeof timestamp === "number" ? timestamp : new Date(timestamp).getTime()
        var diffMs = now - ts
        var diffSec = Math.floor(diffMs / 1000)
        if (diffSec < 60) return "Just now"
        var diffMin = Math.floor(diffSec / 60)
        if (diffMin < 60) return diffMin + (diffMin === 1 ? " minute ago" : " minutes ago")
        var diffHour = Math.floor(diffMin / 60)
        if (diffHour < 24) return diffHour + (diffHour === 1 ? " hour ago" : " hours ago")
        var diffDay = Math.floor(diffHour / 24)
        if (diffDay < 30) return diffDay + (diffDay === 1 ? " day ago" : " days ago")
        var diffMonth = Math.floor(diffDay / 30)
        if (diffMonth < 12) return diffMonth + (diffMonth === 1 ? " month ago" : " months ago")
        var diffYear = Math.floor(diffMonth / 12)
        return diffYear + (diffYear === 1 ? " year ago" : " years ago")
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 0

        // Header
        RowLayout {
            Layout.fillWidth: true
            Layout.bottomMargin: 16

            Text {
                text: "Report History"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            Item { Layout.fillWidth: true }

            Text {
                text: filteredReports().length + (filteredReports().length === 1 ? " report" : " reports")
                font.pixelSize: 13
                color: Style.textSecondary
                Layout.rightMargin: 12
            }

            // Refresh button
            Rectangle {
                width: 32
                height: 32
                radius: 6
                color: refreshArea.containsMouse ? Style.cardBorder : Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1

                Text {
                    anchors.centerIn: parent
                    text: "\u21BB"
                    font.pixelSize: 16
                    color: Style.textPrimary
                }

                MouseArea {
                    id: refreshArea
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    hoverEnabled: true
                    onClicked: refreshReports()
                }
            }
        }

        // Search field
        Rectangle {
            Layout.fillWidth: true
            Layout.bottomMargin: 16
            height: 40
            color: Style.fieldBackground
            border.color: searchInput.activeFocus ? Style.primary : Style.fieldBorder
            border.width: 1
            radius: 8

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 8

                Text {
                    text: "\uD83D\uDD0D"
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                TextInput {
                    id: searchInput
                    Layout.fillWidth: true
                    color: Style.textPrimary
                    font.pixelSize: 14
                    clip: true
                    onTextChanged: root.searchText = text

                    Text {
                        anchors.fill: parent
                        text: "Search reports..."
                        font.pixelSize: 14
                        color: Style.textHint
                        visible: !searchInput.text && !searchInput.activeFocus
                    }
                }

                // Clear search button
                Text {
                    text: "\u2715"
                    font.pixelSize: 12
                    color: Style.textSecondary
                    visible: searchInput.text.length > 0

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: { searchInput.text = ""; root.searchText = "" }
                    }
                }
            }
        }

        // Report detail view (when a report is selected)
        Loader {
            id: detailLoader
            Layout.fillWidth: true
            Layout.fillHeight: true
            active: selectedReport !== null
            visible: active
            sourceComponent: reportDetailComponent
        }

        // Report list (when no report selected)
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: selectedReport === null

            // Empty state
            ColumnLayout {
                anchors.centerIn: parent
                spacing: 12
                visible: filteredReports().length === 0

                Text {
                    text: "\uD83D\uDCCB"
                    font.pixelSize: 48
                    Layout.alignment: Qt.AlignHCenter
                }

                Text {
                    text: reports.length === 0 ? "No reports yet" : "No matching reports"
                    font.pixelSize: 18
                    font.bold: true
                    color: Style.textPrimary
                    Layout.alignment: Qt.AlignHCenter
                }

                Text {
                    text: reports.length === 0
                          ? "Generated reports will appear here"
                          : "Try a different search term"
                    font.pixelSize: 13
                    color: Style.textSecondary
                    Layout.alignment: Qt.AlignHCenter
                }
            }

            // Report list
            ListView {
                id: reportListView
                anchors.fill: parent
                visible: filteredReports().length > 0
                model: filteredReports()
                clip: true
                spacing: 8
                boundsBehavior: Flickable.StopAtBounds

                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                }

                delegate: Rectangle {
                    id: reportCard
                    width: reportListView.width
                    height: cardContent.implicitHeight + 24
                    color: cardMouseArea.containsMouse ? Qt.darker(Style.cardBackground, 0.9) : Style.cardBackground
                    border.color: cardMouseArea.containsMouse ? Style.primary : Style.cardBorder
                    border.width: 1
                    radius: 8

                    property var report: modelData

                    ColumnLayout {
                        id: cardContent
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 12
                        spacing: 6

                        RowLayout {
                            Layout.fillWidth: true

                            Text {
                                text: report.title || "Untitled Report"
                                font.pixelSize: 15
                                font.bold: true
                                color: Style.textPrimary
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }

                            Text {
                                text: (report.results ? report.results.length : 0) + " results"
                                font.pixelSize: 11
                                color: Style.textTertiary
                                Layout.rightMargin: 8
                            }

                            // Delete button
                            Rectangle {
                                width: 28
                                height: 28
                                radius: 6
                                color: deleteArea.containsMouse ? "#33FF4757" : "transparent"

                                Text {
                                    anchors.centerIn: parent
                                    text: "\uD83D\uDDD1"
                                    font.pixelSize: 14
                                }

                                MouseArea {
                                    id: deleteArea
                                    anchors.fill: parent
                                    cursorShape: Qt.PointingHandCursor
                                    hoverEnabled: true
                                    onClicked: {
                                        pendingDeleteId = report.id
                                        showDeleteOneDialog = true
                                    }
                                }
                            }
                        }

                        Text {
                            text: formatRelativeTime(report.timestamp)
                            font.pixelSize: 12
                            color: Style.textSecondary
                        }

                        Text {
                            text: (report.prompt || "").substring(0, 100) + ((report.prompt || "").length > 100 ? "..." : "")
                            font.pixelSize: 12
                            color: Style.textTertiary
                            wrapMode: Text.WordWrap
                            Layout.fillWidth: true
                            maximumLineCount: 2
                            elide: Text.ElideRight
                        }
                    }

                    MouseArea {
                        id: cardMouseArea
                        anchors.fill: parent
                        anchors.rightMargin: 40
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: {
                            var loaded = viewModel.loadReport(report.id)
                            if (loaded && loaded.id) {
                                selectedReport = loaded
                            }
                        }
                    }
                }
            }
        }

        // Footer with Delete All button
        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 12
            visible: selectedReport === null

            Item { Layout.fillWidth: true }

            Rectangle {
                width: deleteAllLabel.implicitWidth + 24
                height: 32
                radius: 6
                color: deleteAllArea.containsMouse ? "#33FF4757" : "transparent"
                border.color: Style.error
                border.width: 1
                visible: reports.length > 0

                Text {
                    id: deleteAllLabel
                    anchors.centerIn: parent
                    text: "Delete All"
                    font.pixelSize: 13
                    color: Style.error
                }

                MouseArea {
                    id: deleteAllArea
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    hoverEnabled: true
                    onClicked: showDeleteAllDialog = true
                }
            }
        }
    }

    // Report detail component
    Component {
        id: reportDetailComponent

        ColumnLayout {
            spacing: 0

            // Back button + title
            RowLayout {
                Layout.fillWidth: true
                Layout.bottomMargin: 16

                Rectangle {
                    width: backLabel.implicitWidth + 20
                    height: 32
                    radius: 6
                    color: backArea.containsMouse ? Style.cardBorder : Style.cardBackground
                    border.color: Style.cardBorder
                    border.width: 1

                    Text {
                        id: backLabel
                        anchors.centerIn: parent
                        text: "\u2190 Back"
                        font.pixelSize: 13
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: backArea
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: selectedReport = null
                    }
                }

                Text {
                    text: selectedReport ? (selectedReport.title || "Untitled") : ""
                    font.pixelSize: 20
                    font.bold: true
                    color: Style.textPrimary
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                }

                // Export button
                Rectangle {
                    width: exportLabel.implicitWidth + 20
                    height: 32
                    radius: 6
                    color: exportArea.containsMouse ? Style.cardBorder : Style.cardBackground
                    border.color: Style.primary
                    border.width: 1

                    Text {
                        id: exportLabel
                        anchors.centerIn: parent
                        text: "Export HTML"
                        font.pixelSize: 13
                        color: Style.primary
                    }

                    MouseArea {
                        id: exportArea
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: {
                            if (selectedReport) {
                                viewModel.openFile(selectedReport.id)
                            }
                        }
                    }
                }
            }

            // Report metadata
            Rectangle {
                Layout.fillWidth: true
                height: metaContent.implicitHeight + 20
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8
                Layout.bottomMargin: 12

                ColumnLayout {
                    id: metaContent
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: 10
                    spacing: 4

                    Text {
                        text: "Prompt"
                        font.pixelSize: 11
                        font.bold: true
                        color: Style.textSecondary
                    }

                    Text {
                        text: selectedReport ? (selectedReport.prompt || "") : ""
                        font.pixelSize: 13
                        color: Style.textPrimary
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }

                    Text {
                        text: selectedReport ? formatRelativeTime(selectedReport.timestamp) : ""
                        font.pixelSize: 11
                        color: Style.textTertiary
                        Layout.topMargin: 4
                    }
                }
            }

            // Results list
            ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                contentWidth: availableWidth
                clip: true

                ColumnLayout {
                    width: parent.width
                    spacing: 10

                    Repeater {
                        model: selectedReport ? (selectedReport.results || []) : []

                        Rectangle {
                            Layout.fillWidth: true
                            implicitHeight: resultContent.implicitHeight + 24
                            color: Style.cardBackground
                            border.color: Style.cardBorder
                            border.width: 1
                            radius: 8

                            ColumnLayout {
                                id: resultContent
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                anchors.margins: 12
                                spacing: 6

                                RowLayout {
                                    Layout.fillWidth: true

                                    Text {
                                        text: modelData.agentName || modelData.providerId || "Agent"
                                        font.pixelSize: 14
                                        font.bold: true
                                        color: Style.primary
                                    }

                                    Text {
                                        text: modelData.model || ""
                                        font.pixelSize: 11
                                        color: Style.textTertiary
                                        Layout.leftMargin: 8
                                    }

                                    Item { Layout.fillWidth: true }

                                    // Token info
                                    Text {
                                        visible: (modelData.inputTokens || 0) > 0
                                        text: (modelData.inputTokens || 0) + " / " + (modelData.outputTokens || 0) + " tokens"
                                        font.pixelSize: 11
                                        color: Style.textTertiary
                                    }
                                }

                                // Error state
                                Text {
                                    visible: !!modelData.error
                                    text: modelData.error || ""
                                    font.pixelSize: 13
                                    color: Style.error
                                    wrapMode: Text.WordWrap
                                    Layout.fillWidth: true
                                }

                                // Analysis content
                                Text {
                                    visible: !!modelData.analysis
                                    text: modelData.analysis || ""
                                    font.pixelSize: 13
                                    color: Style.textPrimary
                                    wrapMode: Text.WordWrap
                                    Layout.fillWidth: true
                                    textFormat: Text.PlainText
                                }
                            }
                        }
                    }

                    Item { height: 20 }
                }
            }
        }
    }

    // Delete single report confirmation dialog
    Dialog {
        id: deleteOneDialog
        title: "Delete Report"
        modal: true
        visible: showDeleteOneDialog
        anchors.centerIn: parent
        width: 360
        standardButtons: Dialog.Ok | Dialog.Cancel

        onAccepted: {
            viewModel.deleteReport(pendingDeleteId)
            refreshReports()
            showDeleteOneDialog = false
            pendingDeleteId = ""
        }
        onRejected: {
            showDeleteOneDialog = false
            pendingDeleteId = ""
        }

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                text: "Are you sure you want to delete this report?"
                font.pixelSize: 14
                color: Style.textPrimary
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Text {
                text: "This action cannot be undone."
                font.pixelSize: 12
                color: Style.textSecondary
            }
        }
    }

    // Delete all reports confirmation dialog
    Dialog {
        id: deleteAllDialog
        title: "Delete All Reports"
        modal: true
        visible: showDeleteAllDialog
        anchors.centerIn: parent
        width: 360
        standardButtons: Dialog.Ok | Dialog.Cancel

        onAccepted: {
            viewModel.deleteAllReports()
            refreshReports()
            selectedReport = null
            showDeleteAllDialog = false
        }
        onRejected: showDeleteAllDialog = false

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                text: "Are you sure you want to delete all " + reports.length + " reports?"
                font.pixelSize: 14
                color: Style.textPrimary
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Text {
                text: "This action cannot be undone."
                font.pixelSize: 12
                color: Style.error
            }
        }
    }
}
