import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var agents: []
    property var providers: []
    property var parametersList: []
    property var systemPrompts: []
    property bool showEditor: false
    property var editingAgent: null
    property var currentProviderModels: []
    property var currentEndpoints: []

    Component.onCompleted: refresh()

    Connections {
        target: viewModel
        function onSettingsChanged() { refresh() }
    }

    function refresh() {
        agents = viewModel.getAgents()
        providers = viewModel.getProviders()
        parametersList = viewModel.getParameters()
        systemPrompts = viewModel.getSystemPrompts()
    }

    function newAgent() {
        editingAgent = {
            id: "",
            name: "",
            providerId: "",
            model: "",
            apiKey: "",
            endpointId: "",
            paramsIds: [],
            systemPromptId: ""
        }
        showEditor = true
        updateProviderModels()
    }

    function editAgent(agent) {
        editingAgent = JSON.parse(JSON.stringify(agent))
        showEditor = true
        updateProviderModels()
    }

    function updateProviderModels() {
        if (editingAgent && editingAgent.providerId) {
            currentProviderModels = viewModel.getProviderModels(editingAgent.providerId)
            currentEndpoints = viewModel.getEndpoints(editingAgent.providerId)
        } else {
            currentProviderModels = []
            currentEndpoints = []
        }
    }

    function getProviderName(pid) {
        for (var i = 0; i < providers.length; i++) {
            if (providers[i].id === pid) return providers[i].displayName
        }
        return pid
    }

    function getEffectiveModel(agent) {
        if (agent.model && agent.model.length > 0) return agent.model
        var config = viewModel.getProviderConfig(agent.providerId)
        return config.model || "(inherited)"
    }

    // Delete confirmation
    Dialog {
        id: deleteDialog
        property string agentId: ""
        property string agentName: ""

        anchors.centerIn: parent
        title: "Delete Agent"
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: Text {
            text: "Delete agent \"" + deleteDialog.agentName + "\"?"
            color: Style.textPrimary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }

        onAccepted: {
            viewModel.deleteAgent(agentId)
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
                        text: (editingAgent && editingAgent.id) ? "Edit Agent" : "New Agent"
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
                        spacing: 12

                        // Name
                        FieldLabel { text: "Name" }
                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: editingAgent ? (editingAgent.name || "") : ""
                            placeholderText: "Agent name"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingAgent) editingAgent.name = text
                        }

                        // Provider
                        FieldLabel { text: "Provider" }
                        ComboBox {
                            id: providerCombo
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            model: {
                                var names = ["(None)"]
                                for (var i = 0; i < providers.length; i++) names.push(providers[i].displayName)
                                return names
                            }
                            currentIndex: {
                                if (!editingAgent || !editingAgent.providerId) return 0
                                for (var i = 0; i < providers.length; i++) {
                                    if (providers[i].id === editingAgent.providerId) return i + 1
                                }
                                return 0
                            }
                            onActivated: {
                                if (currentIndex === 0) {
                                    editingAgent.providerId = ""
                                } else {
                                    editingAgent.providerId = providers[currentIndex - 1].id
                                }
                                updateProviderModels()
                            }
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                            contentItem: Text { text: providerCombo.displayText; color: Style.textPrimary; font.pixelSize: 14; verticalAlignment: Text.AlignVCenter; leftPadding: 8 }
                            popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                            delegate: ItemDelegate {
                                width: providerCombo.width
                                contentItem: Text { text: modelData; color: Style.textPrimary; font.pixelSize: 13 }
                                background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                            }
                        }

                        // Model
                        FieldLabel { text: "Model (empty = inherit from provider)" }
                        ComboBox {
                            id: modelCombo
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            editable: true
                            model: {
                                var items = [""]
                                for (var i = 0; i < currentProviderModels.length; i++) items.push(currentProviderModels[i])
                                return items
                            }
                            editText: editingAgent ? (editingAgent.model || "") : ""
                            onEditTextChanged: if (editingAgent) editingAgent.model = editText
                            onActivated: {
                                if (currentIndex > 0 && editingAgent) {
                                    editingAgent.model = currentProviderModels[currentIndex - 1]
                                }
                            }
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                            contentItem: TextField {
                                text: modelCombo.editText
                                color: Style.textPrimary
                                font.pixelSize: 14
                                verticalAlignment: Text.AlignVCenter
                                leftPadding: 8
                                background: null
                                onTextChanged: modelCombo.editText = text
                            }
                            popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                            delegate: ItemDelegate {
                                width: modelCombo.width
                                contentItem: Text { text: modelData || "(inherit)"; color: modelData ? Style.textPrimary : Style.textHint; font.pixelSize: 13 }
                                background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                            }
                        }

                        // Inherited value hint
                        Text {
                            visible: editingAgent && (!editingAgent.model || editingAgent.model.length === 0) && editingAgent.providerId
                            text: {
                                if (!editingAgent || !editingAgent.providerId) return ""
                                var cfg = viewModel.getProviderConfig(editingAgent.providerId)
                                return "Inherits: " + (cfg.model || "none")
                            }
                            font.pixelSize: 12
                            font.italic: true
                            color: Style.textTertiary
                        }

                        // API Key
                        FieldLabel { text: "API Key (empty = inherit from provider)" }
                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: editingAgent ? (editingAgent.apiKey || "") : ""
                            echoMode: TextInput.Password
                            placeholderText: "Leave empty to use provider key"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingAgent) editingAgent.apiKey = text
                        }

                        // Endpoint
                        FieldLabel { text: "Endpoint"; visible: currentEndpoints.length > 0 }
                        ComboBox {
                            id: endpointCombo
                            visible: currentEndpoints.length > 0
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            model: {
                                var items = ["(Default)"]
                                for (var i = 0; i < currentEndpoints.length; i++) items.push(currentEndpoints[i].name)
                                return items
                            }
                            currentIndex: {
                                if (!editingAgent || !editingAgent.endpointId) return 0
                                for (var i = 0; i < currentEndpoints.length; i++) {
                                    if (currentEndpoints[i].id === editingAgent.endpointId) return i + 1
                                }
                                return 0
                            }
                            onActivated: {
                                if (currentIndex === 0) {
                                    editingAgent.endpointId = ""
                                } else {
                                    editingAgent.endpointId = currentEndpoints[currentIndex - 1].id
                                }
                            }
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                            contentItem: Text { text: endpointCombo.displayText; color: Style.textPrimary; font.pixelSize: 14; verticalAlignment: Text.AlignVCenter; leftPadding: 8 }
                            popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                            delegate: ItemDelegate {
                                width: endpointCombo.width
                                contentItem: Text { text: modelData; color: Style.textPrimary; font.pixelSize: 13 }
                                background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                            }
                        }

                        // Parameter presets
                        FieldLabel { text: "Parameter Presets" }
                        Flow {
                            Layout.fillWidth: true
                            spacing: 8

                            Repeater {
                                model: parametersList.length

                                CheckBox {
                                    required property int index
                                    property var param: parametersList[index]

                                    text: param.name || param.id
                                    checked: editingAgent && editingAgent.paramsIds ? editingAgent.paramsIds.indexOf(param.id) >= 0 : false
                                    onToggled: {
                                        if (!editingAgent) return
                                        var ids = editingAgent.paramsIds || []
                                        if (checked) {
                                            ids.push(param.id)
                                        } else {
                                            ids = ids.filter(function(id) { return id !== param.id })
                                        }
                                        editingAgent.paramsIds = ids
                                    }

                                    contentItem: Text {
                                        text: parent.text
                                        color: Style.textPrimary
                                        font.pixelSize: 13
                                        leftPadding: parent.indicator.width + 4
                                    }
                                }
                            }
                        }

                        // System prompt
                        FieldLabel { text: "System Prompt" }
                        ComboBox {
                            id: sysPromptCombo
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            model: {
                                var items = ["(None)"]
                                for (var i = 0; i < systemPrompts.length; i++) items.push(systemPrompts[i].name)
                                return items
                            }
                            currentIndex: {
                                if (!editingAgent || !editingAgent.systemPromptId) return 0
                                for (var i = 0; i < systemPrompts.length; i++) {
                                    if (systemPrompts[i].id === editingAgent.systemPromptId) return i + 1
                                }
                                return 0
                            }
                            onActivated: {
                                if (currentIndex === 0) {
                                    editingAgent.systemPromptId = ""
                                } else {
                                    editingAgent.systemPromptId = systemPrompts[currentIndex - 1].id
                                }
                            }
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                            contentItem: Text { text: sysPromptCombo.displayText; color: Style.textPrimary; font.pixelSize: 14; verticalAlignment: Text.AlignVCenter; leftPadding: 8 }
                            popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                            delegate: ItemDelegate {
                                width: sysPromptCombo.width
                                contentItem: Text { text: modelData; color: Style.textPrimary; font.pixelSize: 13 }
                                background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                            }
                        }

                        // Save button
                        RowLayout {
                            Layout.topMargin: 8
                            spacing: 12

                            Button {
                                text: "Save Agent"
                                onClicked: {
                                    viewModel.saveAgent(editingAgent)
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
                        text: "Agents"
                        font.pixelSize: 28
                        font.bold: true
                        color: Style.primary
                        Layout.fillWidth: true
                    }

                    Button {
                        text: "+ Add Agent"
                        onClicked: newAgent()
                        background: Rectangle { color: Style.primary; radius: 6 }
                        contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 13; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        leftPadding: 16; rightPadding: 16; topPadding: 6; bottomPadding: 6
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }
                }

                Text {
                    visible: agents.length === 0
                    text: "No agents configured. Add one to get started."
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                Repeater {
                    model: agents.length

                    Rectangle {
                        required property int index

                        Layout.fillWidth: true
                        implicitHeight: agentRow.implicitHeight + 24
                        color: agentMouse.containsMouse ? Qt.lighter(Style.cardBackground, 1.15) : Style.cardBackground
                        border.color: agentMouse.containsMouse ? Style.primary : Style.cardBorder
                        border.width: 1
                        radius: 8

                        property var agent: agents[index]

                        RowLayout {
                            id: agentRow
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 12

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    text: agent.name || "(unnamed)"
                                    font.pixelSize: 15
                                    font.bold: true
                                    color: Style.textPrimary
                                }

                                Text {
                                    text: getProviderName(agent.providerId) + " / " + getEffectiveModel(agent)
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }

                                Text {
                                    visible: (agent.paramsIds || []).length > 0
                                    text: (agent.paramsIds || []).length + " parameter preset(s)"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                }
                            }

                            Button {
                                text: "Edit"
                                onClicked: editAgent(agent)
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Delete"
                                onClicked: {
                                    deleteDialog.agentId = agent.id
                                    deleteDialog.agentName = agent.name
                                    deleteDialog.open()
                                }
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.error; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        MouseArea {
                            id: agentMouse
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
