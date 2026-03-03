import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var providers: []
    property string selectedProviderId: ""
    property var endpoints: []
    property bool showEditor: false
    property var editingEndpoint: null
    property int editingIndex: -1

    Component.onCompleted: {
        providers = viewModel.getProviders()
        if (providers.length > 0) {
            selectedProviderId = providers[0].id
            loadEndpoints()
        }
    }

    Connections {
        target: viewModel
        function onSettingsChanged() {
            providers = viewModel.getProviders()
            loadEndpoints()
        }
    }

    function loadEndpoints() {
        if (selectedProviderId.length > 0) {
            endpoints = viewModel.getEndpoints(selectedProviderId)
        } else {
            endpoints = []
        }
    }

    function newEndpoint() {
        editingEndpoint = {
            id: "",
            name: "",
            url: "",
            isDefault: false
        }
        editingIndex = -1
        showEditor = true
    }

    function editEndpoint(ep, idx) {
        editingEndpoint = JSON.parse(JSON.stringify(ep))
        editingIndex = idx
        showEditor = true
    }

    function saveEndpoint() {
        if (!editingEndpoint) return

        var updated = []
        for (var i = 0; i < endpoints.length; i++) {
            updated.push(JSON.parse(JSON.stringify(endpoints[i])))
        }

        if (editingIndex >= 0) {
            // Editing existing
            updated[editingIndex] = editingEndpoint
        } else {
            // Adding new
            updated.push(editingEndpoint)
        }

        // If new endpoint is default, unset others
        if (editingEndpoint.isDefault) {
            for (var j = 0; j < updated.length; j++) {
                if (j !== (editingIndex >= 0 ? editingIndex : updated.length - 1)) {
                    updated[j].isDefault = false
                }
            }
        }

        viewModel.saveEndpoints(selectedProviderId, updated)
        showEditor = false
        loadEndpoints()
    }

    function deleteEndpoint(idx) {
        var updated = []
        for (var i = 0; i < endpoints.length; i++) {
            if (i !== idx) {
                updated.push(JSON.parse(JSON.stringify(endpoints[i])))
            }
        }
        viewModel.saveEndpoints(selectedProviderId, updated)
        loadEndpoints()
    }

    function getProviderName(pid) {
        for (var i = 0; i < providers.length; i++) {
            if (providers[i].id === pid) return providers[i].displayName
        }
        return pid
    }

    // Delete confirmation
    Dialog {
        id: deleteDialog
        property int endpointIndex: -1
        property string endpointName: ""

        anchors.centerIn: parent
        title: "Delete Endpoint"
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: Text {
            text: "Delete endpoint \"" + deleteDialog.endpointName + "\"?"
            color: Style.textPrimary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }

        onAccepted: {
            deleteEndpoint(endpointIndex)
        }
    }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 16

            // Title
            Text {
                text: "Endpoints"
                font.pixelSize: 28
                font.bold: true
                color: Style.primary
            }

            Text {
                text: "Manage API endpoints for each provider. Multiple endpoints allow routing to different API versions."
                font.pixelSize: 13
                color: Style.textSecondary
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }

            // Provider selector
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: providerSelectorCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: providerSelectorCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text { text: "Provider"; font.pixelSize: 13; color: Style.textSecondary }

                    ComboBox {
                        id: providerCombo
                        Layout.fillWidth: true
                        Layout.maximumWidth: 500
                        model: {
                            var names = []
                            for (var i = 0; i < providers.length; i++) names.push(providers[i].displayName)
                            return names
                        }
                        currentIndex: {
                            for (var i = 0; i < providers.length; i++) {
                                if (providers[i].id === selectedProviderId) return i
                            }
                            return 0
                        }
                        onActivated: {
                            if (currentIndex >= 0 && currentIndex < providers.length) {
                                selectedProviderId = providers[currentIndex].id
                                loadEndpoints()
                            }
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
                }
            }

            // Endpoints list
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: endpointsCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: endpointsCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Text {
                            text: "Endpoints for " + getProviderName(selectedProviderId)
                            font.pixelSize: 16
                            font.bold: true
                            color: Style.textPrimary
                            Layout.fillWidth: true
                        }

                        Button {
                            text: "+ Add Endpoint"
                            onClicked: newEndpoint()
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                            contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                        }

                        Button {
                            text: "Reset to Defaults"
                            onClicked: {
                                viewModel.saveEndpoints(selectedProviderId, [])
                                loadEndpoints()
                            }
                            background: Rectangle { color: Style.fieldBackground; border.color: Style.warning; border.width: 1; radius: 6 }
                            contentItem: Text { text: parent.text; color: Style.warning; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                        }
                    }

                    Text {
                        visible: endpoints.length === 0
                        text: "No custom endpoints. Using provider defaults."
                        font.pixelSize: 13
                        color: Style.textHint
                    }

                    Repeater {
                        model: endpoints.length

                        Rectangle {
                            required property int index

                            Layout.fillWidth: true
                            implicitHeight: epRow.implicitHeight + 16
                            color: epMouse.containsMouse ? Qt.lighter(Style.fieldBackground, 1.15) : Style.fieldBackground
                            border.color: epMouse.containsMouse ? Style.primary : Style.fieldBorder
                            border.width: 1
                            radius: 6

                            property var ep: endpoints[index]

                            RowLayout {
                                id: epRow
                                anchors.fill: parent
                                anchors.margins: 8
                                spacing: 8

                                // Default indicator
                                Rectangle {
                                    width: 8
                                    height: 8
                                    radius: 4
                                    color: ep.isDefault ? Style.success : Style.stateInactive
                                }

                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2

                                    RowLayout {
                                        spacing: 8

                                        Text {
                                            text: ep.name || "(unnamed)"
                                            font.pixelSize: 14
                                            font.bold: true
                                            color: Style.textPrimary
                                        }

                                        Rectangle {
                                            visible: ep.isDefault || false
                                            implicitWidth: defaultLabel.implicitWidth + 12
                                            implicitHeight: 18
                                            radius: 9
                                            color: Style.surfaceVariant

                                            Text {
                                                id: defaultLabel
                                                anchors.centerIn: parent
                                                text: "default"
                                                font.pixelSize: 10
                                                color: Style.success
                                            }
                                        }
                                    }

                                    Text {
                                        text: ep.url || ""
                                        font.pixelSize: 12
                                        color: Style.textTertiary
                                        elide: Text.ElideMiddle
                                        Layout.fillWidth: true
                                    }
                                }

                                Button {
                                    text: "Edit"
                                    onClicked: editEndpoint(ep, index)
                                    background: Rectangle { color: Style.cardBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                    contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                    MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                                }

                                Button {
                                    text: "Delete"
                                    onClicked: {
                                        deleteDialog.endpointIndex = index
                                        deleteDialog.endpointName = ep.name
                                        deleteDialog.open()
                                    }
                                    background: Rectangle { color: Style.cardBackground; border.color: Style.error; border.width: 1; radius: 6 }
                                    contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                    MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                                }
                            }

                            MouseArea {
                                id: epMouse
                                anchors.fill: parent
                                hoverEnabled: true
                                propagateComposedEvents: true
                                z: -1
                            }
                        }
                    }
                }
            }

            // Edit form (inline, below list)
            Rectangle {
                visible: showEditor
                Layout.fillWidth: true
                implicitHeight: editFormCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.primary
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: editFormCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: editingIndex >= 0 ? "Edit Endpoint" : "New Endpoint"
                        font.pixelSize: 16
                        font.bold: true
                        color: Style.primary
                    }

                    // Name
                    Text { text: "Name"; font.pixelSize: 13; color: Style.textSecondary }
                    TextField {
                        Layout.fillWidth: true
                        Layout.maximumWidth: 500
                        text: editingEndpoint ? (editingEndpoint.name || "") : ""
                        placeholderText: "Endpoint name"
                        color: Style.textPrimary
                        placeholderTextColor: Style.textHint
                        font.pixelSize: 14
                        background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                        onTextChanged: if (editingEndpoint) editingEndpoint.name = text
                    }

                    // URL
                    Text { text: "URL"; font.pixelSize: 13; color: Style.textSecondary }
                    TextField {
                        Layout.fillWidth: true
                        text: editingEndpoint ? (editingEndpoint.url || "") : ""
                        placeholderText: "https://api.example.com/v1/chat/completions"
                        color: Style.textPrimary
                        placeholderTextColor: Style.textHint
                        font.pixelSize: 14
                        background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                        onTextChanged: if (editingEndpoint) editingEndpoint.url = text
                    }

                    // Is Default
                    RowLayout {
                        spacing: 12
                        Text { text: "Default Endpoint"; font.pixelSize: 14; color: Style.textPrimary }
                        Switch {
                            checked: editingEndpoint ? (editingEndpoint.isDefault || false) : false
                            onToggled: if (editingEndpoint) editingEndpoint.isDefault = checked
                        }
                    }

                    // Save / Cancel
                    RowLayout {
                        Layout.topMargin: 8
                        spacing: 12

                        Button {
                            text: "Save Endpoint"
                            onClicked: saveEndpoint()
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
