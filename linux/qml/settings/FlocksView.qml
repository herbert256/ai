import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var flocks: []
    property var agents: []
    property var parametersList: []
    property var systemPrompts: []
    property bool showEditor: false
    property var editingFlock: null

    Component.onCompleted: refresh()

    Connections {
        target: viewModel
        function onSettingsChanged() { refresh() }
    }

    function refresh() {
        flocks = viewModel.getFlocks()
        agents = viewModel.getAgents()
        parametersList = viewModel.getParameters()
        systemPrompts = viewModel.getSystemPrompts()
    }

    function newFlock() {
        editingFlock = {
            id: "",
            name: "",
            agentIds: [],
            paramsIds: [],
            systemPromptId: ""
        }
        showEditor = true
    }

    function editFlock(flock) {
        editingFlock = JSON.parse(JSON.stringify(flock))
        showEditor = true
    }

    function getAgentName(agentId) {
        for (var i = 0; i < agents.length; i++) {
            if (agents[i].id === agentId) return agents[i].name
        }
        return agentId
    }

    // Delete confirmation
    Dialog {
        id: deleteDialog
        property string flockId: ""
        property string flockName: ""

        anchors.centerIn: parent
        title: "Delete Flock"
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: Text {
            text: "Delete flock \"" + deleteDialog.flockName + "\"?"
            color: Style.textPrimary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }

        onAccepted: {
            viewModel.deleteFlock(flockId)
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
                        text: (editingFlock && editingFlock.id) ? "Edit Flock" : "New Flock"
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
                        Text { text: "Name"; font.pixelSize: 13; color: Style.textSecondary }
                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: editingFlock ? (editingFlock.name || "") : ""
                            placeholderText: "Flock name"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingFlock) editingFlock.name = text
                        }

                        // Agent selection
                        Text { text: "Agents"; font.pixelSize: 13; color: Style.textSecondary }

                        Text {
                            visible: agents.length === 0
                            text: "No agents available. Create agents first."
                            font.pixelSize: 12
                            color: Style.textHint
                        }

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4

                            Repeater {
                                model: agents.length

                                CheckBox {
                                    required property int index
                                    property var agent: agents[index]

                                    text: agent.name || agent.id
                                    checked: editingFlock && editingFlock.agentIds ? editingFlock.agentIds.indexOf(agent.id) >= 0 : false
                                    onToggled: {
                                        if (!editingFlock) return
                                        var ids = editingFlock.agentIds || []
                                        if (checked) {
                                            ids.push(agent.id)
                                        } else {
                                            ids = ids.filter(function(id) { return id !== agent.id })
                                        }
                                        editingFlock.agentIds = ids
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

                        // Parameter presets
                        Text { text: "Parameter Presets"; font.pixelSize: 13; color: Style.textSecondary }
                        Flow {
                            Layout.fillWidth: true
                            spacing: 8

                            Repeater {
                                model: parametersList.length

                                CheckBox {
                                    required property int index
                                    property var param: parametersList[index]

                                    text: param.name || param.id
                                    checked: editingFlock && editingFlock.paramsIds ? editingFlock.paramsIds.indexOf(param.id) >= 0 : false
                                    onToggled: {
                                        if (!editingFlock) return
                                        var ids = editingFlock.paramsIds || []
                                        if (checked) {
                                            ids.push(param.id)
                                        } else {
                                            ids = ids.filter(function(id) { return id !== param.id })
                                        }
                                        editingFlock.paramsIds = ids
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
                        Text { text: "System Prompt"; font.pixelSize: 13; color: Style.textSecondary }
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
                                if (!editingFlock || !editingFlock.systemPromptId) return 0
                                for (var i = 0; i < systemPrompts.length; i++) {
                                    if (systemPrompts[i].id === editingFlock.systemPromptId) return i + 1
                                }
                                return 0
                            }
                            onActivated: {
                                if (currentIndex === 0) {
                                    editingFlock.systemPromptId = ""
                                } else {
                                    editingFlock.systemPromptId = systemPrompts[currentIndex - 1].id
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

                        // Save
                        RowLayout {
                            Layout.topMargin: 8
                            spacing: 12

                            Button {
                                text: "Save Flock"
                                onClicked: {
                                    viewModel.saveFlock(editingFlock)
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
                        text: "Flocks"
                        font.pixelSize: 28
                        font.bold: true
                        color: Style.primary
                        Layout.fillWidth: true
                    }

                    Button {
                        text: "+ Add Flock"
                        onClicked: newFlock()
                        background: Rectangle { color: Style.primary; radius: 6 }
                        contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 13; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        leftPadding: 16; rightPadding: 16; topPadding: 6; bottomPadding: 6
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }
                }

                Text {
                    visible: flocks.length === 0
                    text: "No flocks configured. A flock is a group of agents that work together."
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                Repeater {
                    model: flocks.length

                    Rectangle {
                        required property int index

                        Layout.fillWidth: true
                        implicitHeight: flockRow.implicitHeight + 24
                        color: flockMouse.containsMouse ? Qt.lighter(Style.cardBackground, 1.15) : Style.cardBackground
                        border.color: flockMouse.containsMouse ? Style.primary : Style.cardBorder
                        border.width: 1
                        radius: 8

                        property var flock: flocks[index]

                        RowLayout {
                            id: flockRow
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 12

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    text: flock.name || "(unnamed)"
                                    font.pixelSize: 15
                                    font.bold: true
                                    color: Style.textPrimary
                                }

                                Text {
                                    text: (flock.agentIds || []).length + " agent(s)"
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }

                                Text {
                                    visible: (flock.agentIds || []).length > 0
                                    text: {
                                        var names = []
                                        var ids = flock.agentIds || []
                                        for (var i = 0; i < Math.min(ids.length, 3); i++) {
                                            names.push(getAgentName(ids[i]))
                                        }
                                        if (ids.length > 3) names.push("...")
                                        return names.join(", ")
                                    }
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }

                            Button {
                                text: "Edit"
                                onClicked: editFlock(flock)
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Delete"
                                onClicked: {
                                    deleteDialog.flockId = flock.id
                                    deleteDialog.flockName = flock.name
                                    deleteDialog.open()
                                }
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.error; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        MouseArea {
                            id: flockMouse
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
}
