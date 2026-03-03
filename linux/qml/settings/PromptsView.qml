import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var prompts: []
    property var agents: []
    property bool showEditor: false
    property var editingPrompt: null

    Component.onCompleted: refresh()

    Connections {
        target: viewModel
        function onSettingsChanged() { refresh() }
    }

    function refresh() {
        prompts = viewModel.getPrompts()
        agents = viewModel.getAgents()
    }

    function newPrompt() {
        editingPrompt = {
            id: "",
            name: "",
            agentId: "",
            promptText: ""
        }
        showEditor = true
    }

    function editPrompt(prompt) {
        editingPrompt = JSON.parse(JSON.stringify(prompt))
        showEditor = true
    }

    function getAgentName(agentId) {
        for (var i = 0; i < agents.length; i++) {
            if (agents[i].id === agentId) return agents[i].name
        }
        return agentId || "(none)"
    }

    // Delete confirmation
    Dialog {
        id: deleteDialog
        property string promptId: ""
        property string promptName: ""

        anchors.centerIn: parent
        title: "Delete Prompt"
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: Text {
            text: "Delete prompt \"" + deleteDialog.promptName + "\"?"
            color: Style.textPrimary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }

        onAccepted: {
            viewModel.deletePrompt(promptId)
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
                        text: (editingPrompt && editingPrompt.id) ? "Edit Prompt" : "New Prompt"
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
                            text: editingPrompt ? (editingPrompt.name || "") : ""
                            placeholderText: "Prompt name"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingPrompt) editingPrompt.name = text
                        }

                        // Agent
                        Text { text: "Agent"; font.pixelSize: 13; color: Style.textSecondary }
                        ComboBox {
                            id: agentCombo
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            model: {
                                var items = ["(None)"]
                                for (var i = 0; i < agents.length; i++) items.push(agents[i].name || agents[i].id)
                                return items
                            }
                            currentIndex: {
                                if (!editingPrompt || !editingPrompt.agentId) return 0
                                for (var i = 0; i < agents.length; i++) {
                                    if (agents[i].id === editingPrompt.agentId) return i + 1
                                }
                                return 0
                            }
                            onActivated: {
                                if (currentIndex === 0) {
                                    editingPrompt.agentId = ""
                                } else {
                                    editingPrompt.agentId = agents[currentIndex - 1].id
                                }
                            }
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                            contentItem: Text { text: agentCombo.displayText; color: Style.textPrimary; font.pixelSize: 14; verticalAlignment: Text.AlignVCenter; leftPadding: 8 }
                            popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                            delegate: ItemDelegate {
                                width: agentCombo.width
                                contentItem: Text { text: modelData; color: Style.textPrimary; font.pixelSize: 13 }
                                background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                            }
                        }

                        // Prompt text
                        Text { text: "Prompt Text"; font.pixelSize: 13; color: Style.textSecondary }
                        ScrollView {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 200

                            TextArea {
                                text: editingPrompt ? (editingPrompt.promptText || "") : ""
                                placeholderText: "Enter prompt text..."
                                color: Style.textPrimary
                                placeholderTextColor: Style.textHint
                                font.pixelSize: 14
                                wrapMode: TextEdit.Wrap
                                background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                                onTextChanged: if (editingPrompt) editingPrompt.promptText = text
                            }
                        }

                        // Template variables info
                        Rectangle {
                            Layout.fillWidth: true
                            implicitHeight: varsCol.implicitHeight + 16
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 6

                            ColumnLayout {
                                id: varsCol
                                anchors.fill: parent
                                anchors.margins: 8
                                spacing: 4

                                Text {
                                    text: "Template Variables"
                                    font.pixelSize: 12
                                    font.bold: true
                                    color: Style.textSecondary
                                }

                                Text {
                                    text: "@MODEL@ - Current model name"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    font.family: "monospace"
                                }

                                Text {
                                    text: "@PROVIDER@ - Current provider name"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    font.family: "monospace"
                                }

                                Text {
                                    text: "@AGENT@ - Current agent name"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    font.family: "monospace"
                                }

                                Text {
                                    text: "@SWARM@ - Current swarm name"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    font.family: "monospace"
                                }

                                Text {
                                    text: "@NOW@ - Current date/time (ISO)"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    font.family: "monospace"
                                }

                                Text {
                                    text: "@FEN@ - Chess FEN position"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    font.family: "monospace"
                                }

                                Text {
                                    text: "@DATE@ - Current date"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    font.family: "monospace"
                                }
                            }
                        }

                        // Save
                        RowLayout {
                            Layout.topMargin: 8
                            spacing: 12

                            Button {
                                text: "Save Prompt"
                                onClicked: {
                                    viewModel.savePrompt(editingPrompt)
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
                        text: "Prompts"
                        font.pixelSize: 28
                        font.bold: true
                        color: Style.primary
                        Layout.fillWidth: true
                    }

                    Button {
                        text: "+ Add Prompt"
                        onClicked: newPrompt()
                        background: Rectangle { color: Style.primary; radius: 6 }
                        contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 13; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        leftPadding: 16; rightPadding: 16; topPadding: 6; bottomPadding: 6
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }
                }

                Text {
                    visible: prompts.length === 0
                    text: "No prompts configured. Prompts are reusable templates for AI queries."
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                Repeater {
                    model: prompts.length

                    Rectangle {
                        required property int index

                        Layout.fillWidth: true
                        implicitHeight: promptRow.implicitHeight + 24
                        color: promptMouse.containsMouse ? Qt.lighter(Style.cardBackground, 1.15) : Style.cardBackground
                        border.color: promptMouse.containsMouse ? Style.primary : Style.cardBorder
                        border.width: 1
                        radius: 8

                        property var prompt: prompts[index]

                        RowLayout {
                            id: promptRow
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 12

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    text: prompt.name || "(unnamed)"
                                    font.pixelSize: 15
                                    font.bold: true
                                    color: Style.textPrimary
                                }

                                Text {
                                    text: "Agent: " + getAgentName(prompt.agentId)
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }

                                Text {
                                    text: (prompt.promptText || "").substring(0, 80) + ((prompt.promptText || "").length > 80 ? "..." : "")
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }

                            Button {
                                text: "Edit"
                                onClicked: editPrompt(prompt)
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Delete"
                                onClicked: {
                                    deleteDialog.promptId = prompt.id
                                    deleteDialog.promptName = prompt.name
                                    deleteDialog.open()
                                }
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.error; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        MouseArea {
                            id: promptMouse
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
