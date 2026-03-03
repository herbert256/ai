import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property var selectedAgentIds: ({})
    property var selectedFlockIds: ({})
    property var selectedSwarmIds: ({})
    property bool showAdvanced: false
    property bool showSelectionDialog: false

    // Collect all checked agent IDs from the three maps
    function collectAgentIds() {
        var ids = []
        for (var key in selectedAgentIds) {
            if (selectedAgentIds[key]) ids.push(key)
        }
        // Expand flocks: each flock contributes its agent members
        var flocks = viewModel.getFlocks()
        for (var fk in selectedFlockIds) {
            if (selectedFlockIds[fk]) {
                for (var i = 0; i < flocks.length; i++) {
                    if (flocks[i].id === fk) {
                        var agentList = flocks[i].agentIds || []
                        for (var a = 0; a < agentList.length; a++) {
                            if (ids.indexOf(agentList[a]) === -1)
                                ids.push(agentList[a])
                        }
                    }
                }
            }
        }
        return ids
    }

    function collectSwarmIds() {
        var ids = []
        for (var key in selectedSwarmIds) {
            if (selectedSwarmIds[key]) ids.push(key)
        }
        return ids
    }

    function selectionCount() {
        var count = 0
        for (var k in selectedAgentIds)  if (selectedAgentIds[k])  count++
        for (var f in selectedFlockIds)  if (selectedFlockIds[f])  count++
        for (var s in selectedSwarmIds)  if (selectedSwarmIds[s])  count++
        return count
    }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 16

            // Header
            Text {
                text: "New Report"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            Text {
                text: "Generate AI-powered analysis reports using multiple providers"
                font.pixelSize: 13
                color: Style.textSecondary
            }

            // Title field
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 72
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 6

                    Text {
                        text: "Report Title"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                    }

                    TextField {
                        id: titleField
                        Layout.fillWidth: true
                        placeholderText: "Enter a title for this report..."
                        placeholderTextColor: Style.textHint
                        color: Style.textPrimary
                        font.pixelSize: 14
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: titleField.activeFocus ? Style.primary : Style.fieldBorder
                            border.width: 1
                            radius: 4
                        }
                        padding: 8
                    }
                }
            }

            // Prompt area
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 220
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 6

                    Text {
                        text: "Prompt"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                    }

                    ScrollView {
                        Layout.fillWidth: true
                        Layout.fillHeight: true

                        TextArea {
                            id: promptArea
                            placeholderText: "Enter your analysis prompt..."
                            placeholderTextColor: Style.textHint
                            color: Style.textPrimary
                            font.pixelSize: 14
                            wrapMode: TextEdit.Wrap
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: promptArea.activeFocus ? Style.primary : Style.fieldBorder
                                border.width: 1
                                radius: 4
                            }
                            padding: 8
                        }
                    }
                }
            }

            // Agent/Flock/Swarm selection
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: selectionColumn.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: selectionColumn
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: 16
                    spacing: 12

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Text {
                            text: "Select AI Sources"
                            font.pixelSize: 14
                            font.bold: true
                            color: Style.textPrimary
                        }

                        Item { Layout.fillWidth: true }

                        Rectangle {
                            Layout.preferredWidth: selCountText.implicitWidth + 16
                            Layout.preferredHeight: 24
                            radius: 12
                            color: selectionCount() > 0 ? Style.primary : Style.fieldBackground
                            visible: selectionCount() > 0

                            Text {
                                id: selCountText
                                anchors.centerIn: parent
                                text: selectionCount() + " selected"
                                font.pixelSize: 11
                                color: Style.textPrimary
                            }
                        }

                        Rectangle {
                            Layout.preferredWidth: chooseBtnText.implicitWidth + 24
                            Layout.preferredHeight: 32
                            radius: 4
                            color: chooseBtnMa.containsMouse ? Style.secondary : Style.primary

                            Text {
                                id: chooseBtnText
                                anchors.centerIn: parent
                                text: "Choose..."
                                font.pixelSize: 12
                                color: Style.textPrimary
                            }

                            MouseArea {
                                id: chooseBtnMa
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                hoverEnabled: true
                                onClicked: root.showSelectionDialog = true
                            }
                        }
                    }

                    // Agents section
                    Text {
                        text: "Agents"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                        visible: agentRepeater.count > 0
                    }

                    Repeater {
                        id: agentRepeater
                        model: viewModel.getAgents()

                        SelectableItem {
                            Layout.fillWidth: true
                            itemName: modelData.name || ""
                            itemDetail: (modelData.providerId || "") + " / " + (modelData.model || "")
                            itemType: "agent"
                            checked: !!selectedAgentIds[modelData.id]
                            onToggled: {
                                var copy = selectedAgentIds
                                copy[modelData.id] = !checked
                                selectedAgentIds = copy
                            }
                        }
                    }

                    // Flocks section
                    Text {
                        text: "Flocks"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                        visible: flockRepeater.count > 0
                        Layout.topMargin: 4
                    }

                    Repeater {
                        id: flockRepeater
                        model: viewModel.getFlocks()

                        SelectableItem {
                            Layout.fillWidth: true
                            itemName: modelData.name || ""
                            itemDetail: {
                                var agentIds = modelData.agentIds || []
                                return agentIds.length + " agent" + (agentIds.length !== 1 ? "s" : "")
                            }
                            itemType: "flock"
                            checked: !!selectedFlockIds[modelData.id]
                            onToggled: {
                                var copy = selectedFlockIds
                                copy[modelData.id] = !checked
                                selectedFlockIds = copy
                            }
                        }
                    }

                    // Swarms section
                    Text {
                        text: "Swarms"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                        visible: swarmRepeater.count > 0
                        Layout.topMargin: 4
                    }

                    Repeater {
                        id: swarmRepeater
                        model: viewModel.getSwarms()

                        SelectableItem {
                            Layout.fillWidth: true
                            itemName: modelData.name || ""
                            itemDetail: {
                                var members = modelData.members || []
                                return members.length + " member" + (members.length !== 1 ? "s" : "")
                            }
                            itemType: "swarm"
                            checked: !!selectedSwarmIds[modelData.id]
                            onToggled: {
                                var copy = selectedSwarmIds
                                copy[modelData.id] = !checked
                                selectedSwarmIds = copy
                            }
                        }
                    }

                    // Empty state
                    Text {
                        visible: agentRepeater.count === 0 && flockRepeater.count === 0 && swarmRepeater.count === 0
                        text: "No agents, flocks, or swarms configured. Set them up in AI Setup."
                        font.pixelSize: 13
                        color: Style.textHint
                        font.italic: true
                    }
                }
            }

            // Advanced parameters (collapsible)
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: advancedColumn.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: advancedColumn
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: 16
                    spacing: 12

                    // Header row (clickable to toggle)
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8

                        Text {
                            text: showAdvanced ? "\u25BC" : "\u25B6"
                            font.pixelSize: 12
                            color: Style.textSecondary
                        }

                        Text {
                            text: "Advanced Parameters"
                            font.pixelSize: 14
                            font.bold: true
                            color: Style.textPrimary
                        }

                        Item { Layout.fillWidth: true }

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: showAdvanced = !showAdvanced
                        }
                    }

                    // Parameter sliders (visible when expanded)
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 16
                        visible: showAdvanced

                        // Temperature
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4

                            RowLayout {
                                Layout.fillWidth: true
                                Text {
                                    text: "Temperature"
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }
                                Item { Layout.fillWidth: true }
                                Text {
                                    text: tempSlider.value.toFixed(1)
                                    font.pixelSize: 12
                                    color: Style.primary
                                }
                            }

                            Slider {
                                id: tempSlider
                                Layout.fillWidth: true
                                from: 0; to: 2; stepSize: 0.1
                                value: 0.7
                                palette.dark: Style.primary
                                palette.midlight: Style.fieldBackground
                            }
                        }

                        // Max Tokens
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4

                            RowLayout {
                                Layout.fillWidth: true
                                Text {
                                    text: "Max Tokens"
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }
                                Item { Layout.fillWidth: true }
                                Text {
                                    text: maxTokensSlider.value.toFixed(0)
                                    font.pixelSize: 12
                                    color: Style.primary
                                }
                            }

                            Slider {
                                id: maxTokensSlider
                                Layout.fillWidth: true
                                from: 1; to: 128000; stepSize: 100
                                value: 4096
                                palette.dark: Style.primary
                                palette.midlight: Style.fieldBackground
                            }
                        }

                        // Top P
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4

                            RowLayout {
                                Layout.fillWidth: true
                                Text {
                                    text: "Top P"
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }
                                Item { Layout.fillWidth: true }
                                Text {
                                    text: topPSlider.value.toFixed(2)
                                    font.pixelSize: 12
                                    color: Style.primary
                                }
                            }

                            Slider {
                                id: topPSlider
                                Layout.fillWidth: true
                                from: 0; to: 1; stepSize: 0.05
                                value: 1.0
                                palette.dark: Style.primary
                                palette.midlight: Style.fieldBackground
                            }
                        }
                    }
                }
            }

            // Generate button
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 48
                radius: 8
                color: generateBtnEnabled ? (generateBtnMa.containsMouse ? Style.secondary : Style.primary) : Style.fieldBackground

                property bool generateBtnEnabled: titleField.text.trim().length > 0
                                                  && promptArea.text.trim().length > 0
                                                  && selectionCount() > 0

                Text {
                    anchors.centerIn: parent
                    text: "Generate Report"
                    font.pixelSize: 16
                    font.bold: true
                    color: parent.generateBtnEnabled ? Style.textPrimary : Style.textHint
                }

                MouseArea {
                    id: generateBtnMa
                    anchors.fill: parent
                    cursorShape: parent.generateBtnEnabled ? Qt.PointingHandCursor : Qt.ArrowCursor
                    hoverEnabled: true
                    onClicked: {
                        if (!parent.generateBtnEnabled) return

                        var agentIds = collectAgentIds()
                        var swarmIds = collectSwarmIds()
                        viewModel.generateReports(
                            titleField.text.trim(),
                            promptArea.text.trim(),
                            agentIds,
                            swarmIds,
                            []  // directModelIds
                        )
                    }
                }
            }

            Item { Layout.preferredHeight: 20 }
        }
    }

    // Selection dialog overlay
    Loader {
        anchors.fill: parent
        active: showSelectionDialog
        sourceComponent: ReportSelectionDialog {
            anchors.fill: parent
            onAccepted: function(agents, flocks, swarms) {
                selectedAgentIds = agents
                selectedFlockIds = flocks
                selectedSwarmIds = swarms
                showSelectionDialog = false
            }
            onCancelled: showSelectionDialog = false
            initialAgentIds: selectedAgentIds
            initialFlockIds: selectedFlockIds
            initialSwarmIds: selectedSwarmIds
        }
    }

    // Report progress overlay
    Loader {
        anchors.fill: parent
        active: viewModel.showReportProgress
        sourceComponent: ReportProgressView {
            anchors.fill: parent
        }
    }

    // Inline component for selectable items
    component SelectableItem: Rectangle {
        id: selectableRoot
        property string itemName
        property string itemDetail
        property string itemType
        property bool checked: false
        signal toggled()

        height: 40
        color: checked ? Qt.rgba(Style.primary.r, Style.primary.g, Style.primary.b, 0.1) : "transparent"
        border.color: checked ? Style.primary : Style.fieldBorder
        border.width: 1
        radius: 4

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 12
            anchors.rightMargin: 12
            spacing: 10

            CheckBox {
                id: cb
                checked: selectableRoot.checked
                onToggled: selectableRoot.toggled()
                palette.highlight: Style.primary
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 1

                Text {
                    text: itemName
                    font.pixelSize: 13
                    color: Style.textPrimary
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }

                Text {
                    text: itemDetail
                    font.pixelSize: 11
                    color: Style.textTertiary
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
            }

            Rectangle {
                Layout.preferredWidth: typeLabel.implicitWidth + 12
                Layout.preferredHeight: 20
                radius: 10
                color: {
                    if (itemType === "agent") return Qt.rgba(Style.primary.r, Style.primary.g, Style.primary.b, 0.2)
                    if (itemType === "flock") return Qt.rgba(Style.success.r, Style.success.g, Style.success.b, 0.2)
                    return Qt.rgba(Style.warning.r, Style.warning.g, Style.warning.b, 0.2)
                }

                Text {
                    id: typeLabel
                    anchors.centerIn: parent
                    text: itemType
                    font.pixelSize: 10
                    font.capitalization: Font.AllUppercase
                    color: {
                        if (itemType === "agent") return Style.primary
                        if (itemType === "flock") return Style.success
                        return Style.warning
                    }
                }
            }
        }

        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: selectableRoot.toggled()
        }
    }
}
