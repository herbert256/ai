import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var swarms: []
    property var providers: []
    property var parametersList: []
    property var systemPrompts: []
    property bool showEditor: false
    property var editingSwarm: null

    Component.onCompleted: refresh()

    Connections {
        target: viewModel
        function onSettingsChanged() { refresh() }
    }

    function refresh() {
        swarms = viewModel.getSwarms()
        providers = viewModel.getProviders()
        parametersList = viewModel.getParameters()
        systemPrompts = viewModel.getSystemPrompts()
    }

    function newSwarm() {
        editingSwarm = {
            id: "",
            name: "",
            members: [],
            paramsIds: [],
            systemPromptId: ""
        }
        showEditor = true
    }

    function editSwarm(swarm) {
        editingSwarm = JSON.parse(JSON.stringify(swarm))
        showEditor = true
    }

    function addMember() {
        if (!editingSwarm) return
        var members = editingSwarm.members || []
        members.push({ providerId: "", model: "" })
        editingSwarm.members = members
        // Force re-bind
        editingSwarm = JSON.parse(JSON.stringify(editingSwarm))
    }

    function removeMember(idx) {
        if (!editingSwarm) return
        var members = editingSwarm.members || []
        members.splice(idx, 1)
        editingSwarm.members = members
        editingSwarm = JSON.parse(JSON.stringify(editingSwarm))
    }

    function getProviderName(pid) {
        for (var i = 0; i < providers.length; i++) {
            if (providers[i].id === pid) return providers[i].displayName
        }
        return pid
    }

    function getModelsForProvider(pid) {
        if (!pid || pid.length === 0) return []
        return viewModel.getProviderModels(pid)
    }

    // Delete confirmation
    Dialog {
        id: deleteDialog
        property string swarmId: ""
        property string swarmName: ""

        anchors.centerIn: parent
        title: "Delete Swarm"
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: Text {
            text: "Delete swarm \"" + deleteDialog.swarmName + "\"?"
            color: Style.textPrimary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }

        onAccepted: {
            viewModel.deleteSwarm(swarmId)
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
                        text: (editingSwarm && editingSwarm.id) ? "Edit Swarm" : "New Swarm"
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
                            text: editingSwarm ? (editingSwarm.name || "") : ""
                            placeholderText: "Swarm name"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingSwarm) editingSwarm.name = text
                        }

                        // Members header
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12

                            Text {
                                text: "Members"
                                font.pixelSize: 16
                                font.bold: true
                                color: Style.textPrimary
                                Layout.fillWidth: true
                            }

                            Button {
                                text: "+ Add Member"
                                onClicked: addMember()
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        Text {
                            visible: !editingSwarm || (editingSwarm.members || []).length === 0
                            text: "No members yet. Add provider/model combinations."
                            font.pixelSize: 12
                            color: Style.textHint
                        }

                        // Member rows
                        Repeater {
                            model: editingSwarm ? (editingSwarm.members || []).length : 0

                            Rectangle {
                                required property int index

                                Layout.fillWidth: true
                                implicitHeight: memberRow.implicitHeight + 16
                                color: Style.fieldBackground
                                border.color: Style.fieldBorder
                                border.width: 1
                                radius: 6

                                property var member: editingSwarm.members[index]
                                property var memberModels: getModelsForProvider(member.providerId)

                                RowLayout {
                                    id: memberRow
                                    anchors.fill: parent
                                    anchors.margins: 8
                                    spacing: 8

                                    Text {
                                        text: (index + 1) + "."
                                        font.pixelSize: 13
                                        color: Style.textSecondary
                                        Layout.preferredWidth: 24
                                    }

                                    // Provider
                                    ComboBox {
                                        id: memberProviderCombo
                                        Layout.fillWidth: true
                                        Layout.preferredWidth: 200
                                        model: {
                                            var names = ["(Select provider)"]
                                            for (var i = 0; i < providers.length; i++) names.push(providers[i].displayName)
                                            return names
                                        }
                                        currentIndex: {
                                            if (!member.providerId) return 0
                                            for (var i = 0; i < providers.length; i++) {
                                                if (providers[i].id === member.providerId) return i + 1
                                            }
                                            return 0
                                        }
                                        onActivated: {
                                            if (currentIndex === 0) {
                                                editingSwarm.members[index].providerId = ""
                                                editingSwarm.members[index].model = ""
                                            } else {
                                                editingSwarm.members[index].providerId = providers[currentIndex - 1].id
                                                editingSwarm.members[index].model = ""
                                            }
                                            editingSwarm = JSON.parse(JSON.stringify(editingSwarm))
                                        }
                                        background: Rectangle { color: Style.cardBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                                        contentItem: Text { text: memberProviderCombo.displayText; color: Style.textPrimary; font.pixelSize: 12; verticalAlignment: Text.AlignVCenter; leftPadding: 8 }
                                        popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                                        delegate: ItemDelegate {
                                            width: memberProviderCombo.width
                                            contentItem: Text { text: modelData; color: Style.textPrimary; font.pixelSize: 12 }
                                            background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                                        }
                                    }

                                    // Model
                                    ComboBox {
                                        id: memberModelCombo
                                        Layout.fillWidth: true
                                        Layout.preferredWidth: 200
                                        editable: true
                                        model: memberModels
                                        editText: member.model || ""
                                        onEditTextChanged: {
                                            if (editingSwarm) editingSwarm.members[index].model = editText
                                        }
                                        onActivated: {
                                            if (editingSwarm && currentIndex >= 0) {
                                                editingSwarm.members[index].model = memberModels[currentIndex]
                                                editingSwarm = JSON.parse(JSON.stringify(editingSwarm))
                                            }
                                        }
                                        background: Rectangle { color: Style.cardBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                                        contentItem: TextField {
                                            text: memberModelCombo.editText
                                            color: Style.textPrimary
                                            font.pixelSize: 12
                                            verticalAlignment: Text.AlignVCenter
                                            leftPadding: 8
                                            background: null
                                            onTextChanged: memberModelCombo.editText = text
                                        }
                                        popup.background: Rectangle { color: Style.cardBackground; border.color: Style.cardBorder; border.width: 1; radius: 6 }
                                        delegate: ItemDelegate {
                                            width: memberModelCombo.width
                                            contentItem: Text { text: modelData; color: Style.textPrimary; font.pixelSize: 12; elide: Text.ElideRight }
                                            background: Rectangle { color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground }
                                        }
                                    }

                                    // Remove button
                                    Button {
                                        text: "X"
                                        implicitWidth: 28
                                        implicitHeight: 28
                                        onClicked: removeMember(index)
                                        background: Rectangle { color: Style.fieldBackground; border.color: Style.error; border.width: 1; radius: 4 }
                                        contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                                    }
                                }
                            }
                        }

                        // Parameter presets
                        Text { text: "Parameter Presets"; font.pixelSize: 13; color: Style.textSecondary; Layout.topMargin: 8 }
                        Flow {
                            Layout.fillWidth: true
                            spacing: 8

                            Repeater {
                                model: parametersList.length

                                CheckBox {
                                    required property int index
                                    property var param: parametersList[index]

                                    text: param.name || param.id
                                    checked: editingSwarm && editingSwarm.paramsIds ? editingSwarm.paramsIds.indexOf(param.id) >= 0 : false
                                    onToggled: {
                                        if (!editingSwarm) return
                                        var ids = editingSwarm.paramsIds || []
                                        if (checked) {
                                            ids.push(param.id)
                                        } else {
                                            ids = ids.filter(function(id) { return id !== param.id })
                                        }
                                        editingSwarm.paramsIds = ids
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
                                if (!editingSwarm || !editingSwarm.systemPromptId) return 0
                                for (var i = 0; i < systemPrompts.length; i++) {
                                    if (systemPrompts[i].id === editingSwarm.systemPromptId) return i + 1
                                }
                                return 0
                            }
                            onActivated: {
                                if (currentIndex === 0) {
                                    editingSwarm.systemPromptId = ""
                                } else {
                                    editingSwarm.systemPromptId = systemPrompts[currentIndex - 1].id
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
                                text: "Save Swarm"
                                onClicked: {
                                    viewModel.saveSwarm(editingSwarm)
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
                        text: "Swarms"
                        font.pixelSize: 28
                        font.bold: true
                        color: Style.primary
                        Layout.fillWidth: true
                    }

                    Button {
                        text: "+ Add Swarm"
                        onClicked: newSwarm()
                        background: Rectangle { color: Style.primary; radius: 6 }
                        contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 13; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        leftPadding: 16; rightPadding: 16; topPadding: 6; bottomPadding: 6
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }
                }

                Text {
                    visible: swarms.length === 0
                    text: "No swarms configured. A swarm is a group of provider/model combinations."
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                Repeater {
                    model: swarms.length

                    Rectangle {
                        required property int index

                        Layout.fillWidth: true
                        implicitHeight: swarmRow.implicitHeight + 24
                        color: swarmMouse.containsMouse ? Qt.lighter(Style.cardBackground, 1.15) : Style.cardBackground
                        border.color: swarmMouse.containsMouse ? Style.primary : Style.cardBorder
                        border.width: 1
                        radius: 8

                        property var swarm: swarms[index]

                        RowLayout {
                            id: swarmRow
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 12

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    text: swarm.name || "(unnamed)"
                                    font.pixelSize: 15
                                    font.bold: true
                                    color: Style.textPrimary
                                }

                                Text {
                                    text: (swarm.members || []).length + " member(s)"
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                }

                                Text {
                                    visible: (swarm.members || []).length > 0
                                    text: {
                                        var descs = []
                                        var members = swarm.members || []
                                        for (var i = 0; i < Math.min(members.length, 3); i++) {
                                            descs.push(getProviderName(members[i].providerId) + "/" + (members[i].model || "?"))
                                        }
                                        if (members.length > 3) descs.push("...")
                                        return descs.join(", ")
                                    }
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }

                            Button {
                                text: "Edit"
                                onClicked: editSwarm(swarm)
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Delete"
                                onClicked: {
                                    deleteDialog.swarmId = swarm.id
                                    deleteDialog.swarmName = swarm.name
                                    deleteDialog.open()
                                }
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.error; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        MouseArea {
                            id: swarmMouse
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
