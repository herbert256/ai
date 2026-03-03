import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var parametersList: []
    property bool showEditor: false
    property var editingParams: null

    Component.onCompleted: refresh()

    Connections {
        target: viewModel
        function onSettingsChanged() { refresh() }
    }

    function refresh() {
        parametersList = viewModel.getParameters()
    }

    function newParams() {
        editingParams = {
            id: "",
            name: "",
            temperature: null,
            maxTokens: null,
            topP: null,
            topK: null,
            frequencyPenalty: null,
            presencePenalty: null,
            systemPrompt: "",
            stopSequences: "",
            seed: null,
            responseFormatJson: false,
            searchEnabled: false,
            returnCitations: true,
            searchRecency: ""
        }
        showEditor = true
    }

    function editParams(params) {
        editingParams = JSON.parse(JSON.stringify(params))
        // Convert stop sequences array to comma-separated string
        if (editingParams.stopSequences && Array.isArray(editingParams.stopSequences)) {
            editingParams.stopSequencesText = editingParams.stopSequences.join(", ")
        } else {
            editingParams.stopSequencesText = ""
        }
        showEditor = true
    }

    // Delete confirmation
    Dialog {
        id: deleteDialog
        property string paramId: ""
        property string paramName: ""

        anchors.centerIn: parent
        title: "Delete Parameter Preset"
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: Text {
            text: "Delete parameter preset \"" + deleteDialog.paramName + "\"?"
            color: Style.textPrimary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }

        onAccepted: {
            viewModel.deleteParameters(paramId)
            refresh()
        }
    }

    // Editor overlay
    Rectangle {
        anchors.fill: parent
        visible: showEditor
        color: Style.background
        z: 10

        ScrollView {
            anchors.fill: parent
            anchors.margins: 20
            contentWidth: availableWidth

            ColumnLayout {
                width: parent.width
                spacing: 16

                RowLayout {
                    spacing: 12

                    Button {
                        text: "< Back"
                        onClicked: showEditor = false
                        background: Rectangle { color: "transparent" }
                        contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 14 }
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }

                    Text {
                        text: (editingParams && editingParams.id) ? "Edit Parameters" : "New Parameters"
                        font.pixelSize: 24
                        font.bold: true
                        color: Style.primary
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: editorCol.implicitHeight + 40
                    color: Style.cardBackground
                    border.color: Style.cardBorder
                    border.width: 1
                    radius: 8

                    ColumnLayout {
                        id: editorCol
                        anchors.fill: parent
                        anchors.margins: 20
                        spacing: 16

                        // Name
                        FieldLabel { text: "Name" }
                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: editingParams ? (editingParams.name || "") : ""
                            placeholderText: "Preset name"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingParams) editingParams.name = text
                        }

                        // ── Sampling Parameters ──
                        Text { text: "Sampling Parameters"; font.pixelSize: 16; font.bold: true; color: Style.textPrimary; Layout.topMargin: 4 }

                        // Temperature
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            FieldLabel { text: "Temperature"; Layout.preferredWidth: 130 }

                            Slider {
                                id: tempSlider
                                Layout.fillWidth: true
                                Layout.maximumWidth: 300
                                from: 0; to: 2; stepSize: 0.05
                                value: editingParams && editingParams.temperature !== null && editingParams.temperature !== undefined ? editingParams.temperature : 1.0
                                onMoved: if (editingParams) editingParams.temperature = value
                            }

                            Text {
                                text: tempSlider.value.toFixed(2)
                                font.pixelSize: 13
                                color: Style.textPrimary
                                Layout.preferredWidth: 40
                            }

                            Button {
                                text: "Clear"
                                implicitHeight: 24
                                onClicked: if (editingParams) editingParams.temperature = null
                                background: Rectangle { color: "transparent" }
                                contentItem: Text { text: parent.text; color: Style.textTertiary; font.pixelSize: 11 }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        // Max Tokens
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            FieldLabel { text: "Max Tokens"; Layout.preferredWidth: 130 }

                            SpinBox {
                                id: maxTokensSpin
                                from: 1; to: 200000; stepSize: 100
                                value: editingParams && editingParams.maxTokens !== null && editingParams.maxTokens !== undefined ? editingParams.maxTokens : 4096
                                editable: true
                                onValueModified: if (editingParams) editingParams.maxTokens = value

                                background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                                contentItem: TextInput {
                                    text: maxTokensSpin.textFromValue(maxTokensSpin.value, maxTokensSpin.locale)
                                    color: Style.textPrimary
                                    font.pixelSize: 14
                                    horizontalAlignment: Qt.AlignHCenter
                                    verticalAlignment: Qt.AlignVCenter
                                    readOnly: !maxTokensSpin.editable
                                    validator: maxTokensSpin.validator
                                    inputMethodHints: Qt.ImhFormattedNumbersOnly
                                }
                            }

                            Button {
                                text: "Clear"
                                implicitHeight: 24
                                onClicked: if (editingParams) editingParams.maxTokens = null
                                background: Rectangle { color: "transparent" }
                                contentItem: Text { text: parent.text; color: Style.textTertiary; font.pixelSize: 11 }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        // Top P
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            FieldLabel { text: "Top P"; Layout.preferredWidth: 130 }

                            Slider {
                                id: topPSlider
                                Layout.fillWidth: true
                                Layout.maximumWidth: 300
                                from: 0; to: 1; stepSize: 0.05
                                value: editingParams && editingParams.topP !== null && editingParams.topP !== undefined ? editingParams.topP : 1.0
                                onMoved: if (editingParams) editingParams.topP = value
                            }

                            Text {
                                text: topPSlider.value.toFixed(2)
                                font.pixelSize: 13
                                color: Style.textPrimary
                                Layout.preferredWidth: 40
                            }

                            Button {
                                text: "Clear"
                                implicitHeight: 24
                                onClicked: if (editingParams) editingParams.topP = null
                                background: Rectangle { color: "transparent" }
                                contentItem: Text { text: parent.text; color: Style.textTertiary; font.pixelSize: 11 }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        // Top K
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            FieldLabel { text: "Top K"; Layout.preferredWidth: 130 }

                            SpinBox {
                                id: topKSpin
                                from: 0; to: 500; stepSize: 1
                                value: editingParams && editingParams.topK !== null && editingParams.topK !== undefined ? editingParams.topK : 0
                                editable: true
                                onValueModified: if (editingParams) editingParams.topK = value

                                background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                                contentItem: TextInput {
                                    text: topKSpin.textFromValue(topKSpin.value, topKSpin.locale)
                                    color: Style.textPrimary
                                    font.pixelSize: 14
                                    horizontalAlignment: Qt.AlignHCenter
                                    verticalAlignment: Qt.AlignVCenter
                                    readOnly: !topKSpin.editable
                                    validator: topKSpin.validator
                                    inputMethodHints: Qt.ImhFormattedNumbersOnly
                                }
                            }

                            Button {
                                text: "Clear"
                                implicitHeight: 24
                                onClicked: if (editingParams) editingParams.topK = null
                                background: Rectangle { color: "transparent" }
                                contentItem: Text { text: parent.text; color: Style.textTertiary; font.pixelSize: 11 }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        // Frequency Penalty
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            FieldLabel { text: "Frequency Penalty"; Layout.preferredWidth: 130 }

                            Slider {
                                id: freqPenSlider
                                Layout.fillWidth: true
                                Layout.maximumWidth: 300
                                from: -2; to: 2; stepSize: 0.1
                                value: editingParams && editingParams.frequencyPenalty !== null && editingParams.frequencyPenalty !== undefined ? editingParams.frequencyPenalty : 0
                                onMoved: if (editingParams) editingParams.frequencyPenalty = value
                            }

                            Text {
                                text: freqPenSlider.value.toFixed(1)
                                font.pixelSize: 13
                                color: Style.textPrimary
                                Layout.preferredWidth: 40
                            }

                            Button {
                                text: "Clear"
                                implicitHeight: 24
                                onClicked: if (editingParams) editingParams.frequencyPenalty = null
                                background: Rectangle { color: "transparent" }
                                contentItem: Text { text: parent.text; color: Style.textTertiary; font.pixelSize: 11 }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        // Presence Penalty
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            FieldLabel { text: "Presence Penalty"; Layout.preferredWidth: 130 }

                            Slider {
                                id: presPenSlider
                                Layout.fillWidth: true
                                Layout.maximumWidth: 300
                                from: -2; to: 2; stepSize: 0.1
                                value: editingParams && editingParams.presencePenalty !== null && editingParams.presencePenalty !== undefined ? editingParams.presencePenalty : 0
                                onMoved: if (editingParams) editingParams.presencePenalty = value
                            }

                            Text {
                                text: presPenSlider.value.toFixed(1)
                                font.pixelSize: 13
                                color: Style.textPrimary
                                Layout.preferredWidth: 40
                            }

                            Button {
                                text: "Clear"
                                implicitHeight: 24
                                onClicked: if (editingParams) editingParams.presencePenalty = null
                                background: Rectangle { color: "transparent" }
                                contentItem: Text { text: parent.text; color: Style.textTertiary; font.pixelSize: 11 }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        // Seed
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            FieldLabel { text: "Seed"; Layout.preferredWidth: 130 }

                            SpinBox {
                                id: seedSpin
                                from: -1; to: 999999; stepSize: 1
                                value: editingParams && editingParams.seed !== null && editingParams.seed !== undefined ? editingParams.seed : -1
                                editable: true
                                onValueModified: if (editingParams) editingParams.seed = (value === -1 ? null : value)

                                background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                                contentItem: TextInput {
                                    text: seedSpin.value === -1 ? "None" : seedSpin.textFromValue(seedSpin.value, seedSpin.locale)
                                    color: Style.textPrimary
                                    font.pixelSize: 14
                                    horizontalAlignment: Qt.AlignHCenter
                                    verticalAlignment: Qt.AlignVCenter
                                    readOnly: !seedSpin.editable
                                    validator: seedSpin.validator
                                    inputMethodHints: Qt.ImhFormattedNumbersOnly
                                }
                            }
                        }

                        // ── Text Parameters ──
                        Text { text: "Text Parameters"; font.pixelSize: 16; font.bold: true; color: Style.textPrimary; Layout.topMargin: 8 }

                        // System Prompt
                        FieldLabel { text: "System Prompt" }
                        ScrollView {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 100

                            TextArea {
                                text: editingParams ? (editingParams.systemPrompt || "") : ""
                                placeholderText: "Optional system prompt override"
                                color: Style.textPrimary
                                placeholderTextColor: Style.textHint
                                font.pixelSize: 14
                                wrapMode: TextEdit.Wrap
                                background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                                onTextChanged: if (editingParams) editingParams.systemPrompt = text
                            }
                        }

                        // Stop Sequences
                        FieldLabel { text: "Stop Sequences (comma-separated)" }
                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: editingParams ? (editingParams.stopSequencesText || "") : ""
                            placeholderText: "stop1, stop2, stop3"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingParams) editingParams.stopSequencesText = text
                        }

                        // ── Boolean Flags ──
                        Text { text: "Flags"; font.pixelSize: 16; font.bold: true; color: Style.textPrimary; Layout.topMargin: 8 }

                        // Response Format JSON
                        RowLayout {
                            spacing: 12
                            Text { text: "Response Format JSON"; font.pixelSize: 14; color: Style.textPrimary }
                            Switch {
                                checked: editingParams ? (editingParams.responseFormatJson || false) : false
                                onToggled: if (editingParams) editingParams.responseFormatJson = checked
                            }
                        }

                        // Search Enabled
                        RowLayout {
                            spacing: 12
                            Text { text: "Search Enabled"; font.pixelSize: 14; color: Style.textPrimary }
                            Switch {
                                checked: editingParams ? (editingParams.searchEnabled || false) : false
                                onToggled: if (editingParams) editingParams.searchEnabled = checked
                            }
                        }

                        // Return Citations
                        RowLayout {
                            spacing: 12
                            Text { text: "Return Citations"; font.pixelSize: 14; color: Style.textPrimary }
                            Switch {
                                checked: editingParams ? (editingParams.returnCitations !== false) : true
                                onToggled: if (editingParams) editingParams.returnCitations = checked
                            }
                        }

                        // Search Recency
                        FieldLabel { text: "Search Recency" }
                        ComboBox {
                            id: recencyCombo
                            Layout.fillWidth: true
                            Layout.maximumWidth: 300
                            model: ["(None)", "day", "week", "month", "year"]
                            currentIndex: {
                                if (!editingParams || !editingParams.searchRecency) return 0
                                var vals = ["", "day", "week", "month", "year"]
                                var idx = vals.indexOf(editingParams.searchRecency)
                                return idx >= 0 ? idx : 0
                            }
                            onActivated: {
                                if (editingParams) {
                                    editingParams.searchRecency = currentIndex === 0 ? "" : model[currentIndex]
                                }
                            }
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                            contentItem: Text { text: recencyCombo.displayText; color: Style.textPrimary; font.pixelSize: 14; verticalAlignment: Text.AlignVCenter; leftPadding: 8 }
                            popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                            delegate: ItemDelegate {
                                width: recencyCombo.width
                                contentItem: Text { text: modelData; color: Style.textPrimary; font.pixelSize: 13 }
                                background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                            }
                        }

                        // Save
                        RowLayout {
                            Layout.topMargin: 12
                            spacing: 12

                            Button {
                                text: "Save Parameters"
                                onClicked: {
                                    // Convert stop sequences text to array
                                    var data = JSON.parse(JSON.stringify(editingParams))
                                    if (data.stopSequencesText && data.stopSequencesText.length > 0) {
                                        data.stopSequences = data.stopSequencesText.split(",").map(function(s) { return s.trim() }).filter(function(s) { return s.length > 0 })
                                    } else {
                                        data.stopSequences = []
                                    }
                                    delete data.stopSequencesText
                                    viewModel.saveParameters(data)
                                    showEditor = false
                                    refresh()
                                }
                                background: Rectangle { color: Style.primary; radius: 6 }
                                contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 14; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                leftPadding: 20; rightPadding: 20; topPadding: 8; bottomPadding: 8
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Cancel"
                                onClicked: showEditor = false
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.textSecondary; font.pixelSize: 14; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                leftPadding: 20; rightPadding: 20; topPadding: 8; bottomPadding: 8
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }
                    }
                }

                Item { Layout.fillHeight: true }
            }
        }
    }

    // List view
    Item {
        anchors.fill: parent
        visible: !showEditor

        ScrollView {
            anchors.fill: parent
            anchors.margins: 20
            contentWidth: availableWidth

            ColumnLayout {
                width: parent.width
                spacing: 16

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    Text {
                        text: "Parameter Presets"
                        font.pixelSize: 28
                        font.bold: true
                        color: Style.primary
                        Layout.fillWidth: true
                    }

                    Button {
                        text: "+ Add Preset"
                        onClicked: newParams()
                        background: Rectangle { color: Style.primary; radius: 6 }
                        contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 13; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        leftPadding: 16; rightPadding: 16; topPadding: 6; bottomPadding: 6
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }
                }

                Text {
                    visible: parametersList.length === 0
                    text: "No parameter presets. Presets allow you to save and reuse AI parameter configurations."
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                Repeater {
                    model: parametersList.length

                    Rectangle {
                        required property int index

                        Layout.fillWidth: true
                        implicitHeight: paramRow.implicitHeight + 24
                        color: paramMouse.containsMouse ? Qt.lighter(Style.cardBackground, 1.15) : Style.cardBackground
                        border.color: paramMouse.containsMouse ? Style.primary : Style.cardBorder
                        border.width: 1
                        radius: 8

                        property var params: parametersList[index]

                        RowLayout {
                            id: paramRow
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 12

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    text: params.name || "(unnamed)"
                                    font.pixelSize: 15
                                    font.bold: true
                                    color: Style.textPrimary
                                }

                                Text {
                                    text: {
                                        var parts = []
                                        if (params.temperature !== null && params.temperature !== undefined) parts.push("temp=" + params.temperature.toFixed(2))
                                        if (params.maxTokens !== null && params.maxTokens !== undefined) parts.push("max=" + params.maxTokens)
                                        if (params.topP !== null && params.topP !== undefined) parts.push("topP=" + params.topP.toFixed(2))
                                        if (params.searchEnabled) parts.push("search")
                                        if (params.responseFormatJson) parts.push("json")
                                        return parts.length > 0 ? parts.join(", ") : "Default values"
                                    }
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }

                            Button {
                                text: "Edit"
                                onClicked: editParams(params)
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Delete"
                                onClicked: {
                                    deleteDialog.paramId = params.id
                                    deleteDialog.paramName = params.name
                                    deleteDialog.open()
                                }
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.error; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        MouseArea {
                            id: paramMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            propagateComposedEvents: true
                            z: -1
                        }
                    }
                }

                Item { Layout.fillHeight: true }
            }
        }
    }

    component FieldLabel: Text {
        font.pixelSize: 13
        color: Style.textSecondary
    }
}
