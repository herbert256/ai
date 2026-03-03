import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property var traceFiles: []
    property int expandedIndex: -1
    property var expandedTrace: null
    property bool showClearConfirm: false

    Component.onCompleted: refreshTraces()

    function refreshTraces() {
        traceFiles = viewModel.getTraceFiles() || []
        expandedIndex = -1
        expandedTrace = null
    }

    function statusColor(code) {
        if (!code || code === 0) return Style.textSecondary
        if (code >= 200 && code < 300) return Style.success
        if (code >= 400 && code < 500) return Style.warning
        if (code >= 500) return Style.error
        return Style.textSecondary
    }

    function formatTimestamp(ts) {
        if (!ts) return ""
        var d = new Date(ts)
        return d.toLocaleString(Qt.locale(), "yyyy-MM-dd HH:mm:ss")
    }

    function toggleTrace(index) {
        if (expandedIndex === index) {
            expandedIndex = -1
            expandedTrace = null
        } else {
            expandedIndex = index
            expandedTrace = viewModel.getTraceDetail(traceFiles[index].id || traceFiles[index].filename) || null
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 16

        // Title row
        RowLayout {
            Layout.fillWidth: true
            spacing: 12

            Text {
                text: "API Traces"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            Item { Layout.fillWidth: true }

            // Trace count badge
            Rectangle {
                Layout.preferredWidth: countBadgeText.implicitWidth + 16
                Layout.preferredHeight: 24
                radius: 12
                color: Style.surfaceVariant
                visible: root.traceFiles.length > 0

                Text {
                    id: countBadgeText
                    anchors.centerIn: parent
                    text: root.traceFiles.length + " trace" + (root.traceFiles.length !== 1 ? "s" : "")
                    font.pixelSize: 11
                    color: Style.textPrimary
                }
            }

            Rectangle {
                Layout.preferredWidth: refreshText.implicitWidth + 24
                Layout.preferredHeight: 32
                radius: 6
                color: refreshMa.containsMouse ? Style.secondary : Style.primary

                Text {
                    id: refreshText
                    anchors.centerIn: parent
                    text: "Refresh"
                    font.pixelSize: 12
                    font.bold: true
                    color: "#FFFFFF"
                }

                MouseArea {
                    id: refreshMa
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    hoverEnabled: true
                    onClicked: root.refreshTraces()
                }
            }
        }

        // Tracing toggle
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 52
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8

            RowLayout {
                anchors.fill: parent
                anchors.margins: 16
                spacing: 12

                Text {
                    text: "Enable API Tracing"
                    font.pixelSize: 14
                    color: Style.textPrimary
                    Layout.fillWidth: true
                }

                Text {
                    text: viewModel.tracingEnabled ? "Active" : "Inactive"
                    font.pixelSize: 12
                    color: viewModel.tracingEnabled ? Style.success : Style.textTertiary
                }

                Switch {
                    id: tracingSwitch
                    checked: viewModel.tracingEnabled
                    onToggled: viewModel.tracingEnabled = checked
                    palette.highlight: Style.primary
                    palette.dark: Style.fieldBackground
                }
            }
        }

        // Trace list
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
            clip: true

            ListView {
                id: traceList
                anchors.fill: parent
                anchors.margins: 8
                model: root.traceFiles.length
                clip: true
                spacing: 4
                boundsBehavior: Flickable.StopAtBounds

                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                }

                delegate: Rectangle {
                    id: traceDelegate
                    width: traceList.width
                    height: traceContent.implicitHeight + 16
                    color: index % 2 === 0 ? "transparent" : Qt.rgba(Style.fieldBackground.r, Style.fieldBackground.g, Style.fieldBackground.b, 0.3)
                    radius: 6

                    property var trace: root.traceFiles[index]
                    property bool isExpanded: root.expandedIndex === index

                    ColumnLayout {
                        id: traceContent
                        anchors.fill: parent
                        anchors.margins: 8
                        spacing: 8

                        // Summary row (always visible)
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 10

                            // Expand indicator
                            Text {
                                text: traceDelegate.isExpanded ? "\u25BC" : "\u25B6"
                                font.pixelSize: 10
                                color: Style.textSecondary
                            }

                            // Status code badge
                            Rectangle {
                                Layout.preferredWidth: statusText.implicitWidth + 12
                                Layout.preferredHeight: 22
                                radius: 4
                                color: Qt.rgba(root.statusColor(trace.statusCode).r,
                                               root.statusColor(trace.statusCode).g,
                                               root.statusColor(trace.statusCode).b, 0.15)

                                Text {
                                    id: statusText
                                    anchors.centerIn: parent
                                    text: (trace.statusCode || "---").toString()
                                    font.pixelSize: 11
                                    font.bold: true
                                    color: root.statusColor(trace.statusCode)
                                }
                            }

                            // Hostname
                            Text {
                                text: trace.hostname || trace.url || ""
                                font.pixelSize: 12
                                color: Style.textPrimary
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }

                            // Model
                            Text {
                                text: trace.model || ""
                                font.pixelSize: 11
                                color: Style.primary
                                elide: Text.ElideRight
                                Layout.preferredWidth: 160
                                horizontalAlignment: Text.AlignRight
                            }

                            // Timestamp
                            Text {
                                text: root.formatTimestamp(trace.timestamp)
                                font.pixelSize: 11
                                color: Style.textTertiary
                                Layout.preferredWidth: 140
                                horizontalAlignment: Text.AlignRight
                            }

                            MouseArea {
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                onClicked: root.toggleTrace(index)
                            }
                        }

                        // Expanded detail (conditional)
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            visible: traceDelegate.isExpanded && root.expandedTrace !== null

                            Rectangle {
                                Layout.fillWidth: true
                                height: 1
                                color: Style.cardBorder
                            }

                            // Request section
                            Text {
                                text: "Request"
                                font.pixelSize: 13
                                font.bold: true
                                color: Style.primary
                            }

                            // URL and Method
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 8

                                Rectangle {
                                    Layout.preferredWidth: methodText.implicitWidth + 12
                                    Layout.preferredHeight: 22
                                    radius: 4
                                    color: Style.surfaceVariant

                                    Text {
                                        id: methodText
                                        anchors.centerIn: parent
                                        text: (root.expandedTrace ? root.expandedTrace.method : "") || "POST"
                                        font.pixelSize: 11
                                        font.bold: true
                                        color: Style.primary
                                    }
                                }

                                Text {
                                    text: (root.expandedTrace ? root.expandedTrace.url : "") || ""
                                    font.pixelSize: 12
                                    color: Style.textPrimary
                                    elide: Text.ElideMiddle
                                    Layout.fillWidth: true
                                }
                            }

                            // Request headers
                            Text {
                                text: "Headers"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                            }

                            Rectangle {
                                Layout.fillWidth: true
                                Layout.preferredHeight: Math.min(reqHeadersArea.implicitHeight + 16, 120)
                                color: Style.fieldBackground
                                border.color: Style.fieldBorder
                                border.width: 1
                                radius: 4

                                ScrollView {
                                    anchors.fill: parent
                                    anchors.margins: 4

                                    TextArea {
                                        id: reqHeadersArea
                                        text: (root.expandedTrace ? root.expandedTrace.requestHeaders : "") || ""
                                        color: Style.textPrimary
                                        font.pixelSize: 11
                                        font.family: "monospace"
                                        wrapMode: TextEdit.Wrap
                                        readOnly: true
                                        selectByMouse: true
                                        background: Rectangle { color: "transparent" }
                                        padding: 8
                                    }
                                }
                            }

                            // Request body
                            Text {
                                text: "Body"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                            }

                            Rectangle {
                                Layout.fillWidth: true
                                Layout.preferredHeight: Math.min(reqBodyArea.implicitHeight + 16, 200)
                                color: Style.fieldBackground
                                border.color: Style.fieldBorder
                                border.width: 1
                                radius: 4

                                ScrollView {
                                    anchors.fill: parent
                                    anchors.margins: 4

                                    TextArea {
                                        id: reqBodyArea
                                        text: (root.expandedTrace ? root.expandedTrace.requestBody : "") || ""
                                        color: Style.textPrimary
                                        font.pixelSize: 11
                                        font.family: "monospace"
                                        wrapMode: TextEdit.Wrap
                                        readOnly: true
                                        selectByMouse: true
                                        background: Rectangle { color: "transparent" }
                                        padding: 8
                                    }
                                }
                            }

                            Rectangle {
                                Layout.fillWidth: true
                                height: 1
                                color: Style.cardBorder
                            }

                            // Response section
                            Text {
                                text: "Response"
                                font.pixelSize: 13
                                font.bold: true
                                color: Style.success
                            }

                            // Response status
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 8

                                Text {
                                    text: "Status:"
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }

                                Text {
                                    text: ((root.expandedTrace ? root.expandedTrace.statusCode : 0) || 0).toString()
                                    font.pixelSize: 12
                                    font.bold: true
                                    color: root.statusColor(root.expandedTrace ? root.expandedTrace.statusCode : 0)
                                }
                            }

                            // Response headers
                            Text {
                                text: "Headers"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                            }

                            Rectangle {
                                Layout.fillWidth: true
                                Layout.preferredHeight: Math.min(resHeadersArea.implicitHeight + 16, 120)
                                color: Style.fieldBackground
                                border.color: Style.fieldBorder
                                border.width: 1
                                radius: 4

                                ScrollView {
                                    anchors.fill: parent
                                    anchors.margins: 4

                                    TextArea {
                                        id: resHeadersArea
                                        text: (root.expandedTrace ? root.expandedTrace.responseHeaders : "") || ""
                                        color: Style.textPrimary
                                        font.pixelSize: 11
                                        font.family: "monospace"
                                        wrapMode: TextEdit.Wrap
                                        readOnly: true
                                        selectByMouse: true
                                        background: Rectangle { color: "transparent" }
                                        padding: 8
                                    }
                                }
                            }

                            // Response body
                            Text {
                                text: "Body"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                            }

                            Rectangle {
                                Layout.fillWidth: true
                                Layout.preferredHeight: Math.min(resBodyArea.implicitHeight + 16, 300)
                                color: Style.fieldBackground
                                border.color: Style.fieldBorder
                                border.width: 1
                                radius: 4

                                ScrollView {
                                    anchors.fill: parent
                                    anchors.margins: 4

                                    TextArea {
                                        id: resBodyArea
                                        text: (root.expandedTrace ? root.expandedTrace.responseBody : "") || ""
                                        color: Style.textPrimary
                                        font.pixelSize: 11
                                        font.family: "monospace"
                                        wrapMode: TextEdit.Wrap
                                        readOnly: true
                                        selectByMouse: true
                                        background: Rectangle { color: "transparent" }
                                        padding: 8
                                    }
                                }
                            }
                        }
                    }

                    // Click area for the summary row
                    MouseArea {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        height: 38
                        cursorShape: Qt.PointingHandCursor
                        onClicked: root.toggleTrace(index)
                    }
                }

                // Empty state
                Text {
                    visible: root.traceFiles.length === 0
                    anchors.centerIn: parent
                    width: parent.width * 0.8
                    text: "No traces recorded. Enable tracing to start capturing API calls."
                    font.pixelSize: 14
                    color: Style.textHint
                    font.italic: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                }
            }
        }

        // Clear all button
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            visible: root.traceFiles.length > 0

            Item { Layout.fillWidth: true }

            Rectangle {
                Layout.preferredWidth: clearTracesBtnText.implicitWidth + 24
                Layout.preferredHeight: 36
                radius: 6
                color: clearTracesMa.containsMouse ? Qt.darker(Style.error, 1.2) : Style.fieldBackground
                border.color: Style.error
                border.width: 1

                Text {
                    id: clearTracesBtnText
                    anchors.centerIn: parent
                    text: "Clear All Traces"
                    font.pixelSize: 12
                    color: Style.error
                }

                MouseArea {
                    id: clearTracesMa
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    hoverEnabled: true
                    onClicked: root.showClearConfirm = true
                }
            }
        }
    }

    // Clear confirmation dialog overlay
    Rectangle {
        anchors.fill: parent
        color: "#88000000"
        visible: root.showClearConfirm

        MouseArea {
            anchors.fill: parent
            onClicked: root.showClearConfirm = false
        }

        Rectangle {
            anchors.centerIn: parent
            width: 400
            height: clearDlgCol.implicitHeight + 48
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 12

            MouseArea { anchors.fill: parent }

            ColumnLayout {
                id: clearDlgCol
                anchors.fill: parent
                anchors.margins: 24
                spacing: 16

                Text {
                    text: "Clear All Traces"
                    font.pixelSize: 18
                    font.bold: true
                    color: Style.textPrimary
                }

                Text {
                    text: "Are you sure you want to delete all " + root.traceFiles.length + " API traces? This action cannot be undone."
                    font.pixelSize: 13
                    color: Style.textSecondary
                    wrapMode: Text.Wrap
                    Layout.fillWidth: true
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    Item { Layout.fillWidth: true }

                    Rectangle {
                        Layout.preferredWidth: clearCancelText.implicitWidth + 24
                        Layout.preferredHeight: 36
                        radius: 6
                        color: clearCancelMa.containsMouse ? Style.fieldBackground : "transparent"
                        border.color: Style.fieldBorder
                        border.width: 1

                        Text {
                            id: clearCancelText
                            anchors.centerIn: parent
                            text: "Cancel"
                            font.pixelSize: 12
                            color: Style.textPrimary
                        }

                        MouseArea {
                            id: clearCancelMa
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            hoverEnabled: true
                            onClicked: root.showClearConfirm = false
                        }
                    }

                    Rectangle {
                        Layout.preferredWidth: clearConfirmText.implicitWidth + 24
                        Layout.preferredHeight: 36
                        radius: 6
                        color: clearConfirmMa.containsMouse ? Qt.darker(Style.error, 1.2) : Style.error

                        Text {
                            id: clearConfirmText
                            anchors.centerIn: parent
                            text: "Clear All"
                            font.pixelSize: 12
                            font.bold: true
                            color: "#FFFFFF"
                        }

                        MouseArea {
                            id: clearConfirmMa
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            hoverEnabled: true
                            onClicked: {
                                viewModel.clearTraces()
                                root.showClearConfirm = false
                                root.refreshTraces()
                            }
                        }
                    }
                }
            }
        }
    }
}
