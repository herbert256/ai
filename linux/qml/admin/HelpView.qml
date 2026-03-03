import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 20

            // Title
            Text {
                text: "Help"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            // About AI
            HelpSection {
                title: "About AI"

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 8

                    Text {
                        text: "Multi-platform AI-powered reports and chat using 31 AI services. Native apps for Android, macOS, Linux, iOS, and Windows sharing the same architecture, data formats, and provider ecosystem."
                        font.pixelSize: 13
                        color: Style.textPrimary
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                    }

                    RowLayout {
                        spacing: 16

                        Text {
                            text: "Version:"
                            font.pixelSize: 12
                            color: Style.textSecondary
                        }

                        Text {
                            text: viewModel.appVersion || "1.0"
                            font.pixelSize: 12
                            font.bold: true
                            color: Style.primary
                        }

                        Text {
                            text: "Platform:"
                            font.pixelSize: 12
                            color: Style.textSecondary
                        }

                        Text {
                            text: "Linux (Qt6)"
                            font.pixelSize: 12
                            font.bold: true
                            color: Style.primary
                        }
                    }

                    RowLayout {
                        spacing: 16

                        Text {
                            text: "Providers:"
                            font.pixelSize: 12
                            color: Style.textSecondary
                        }

                        Text {
                            text: (viewModel.providerCount || 31).toString() + " AI services"
                            font.pixelSize: 12
                            font.bold: true
                            color: Style.primary
                        }

                        Text {
                            text: "API Formats:"
                            font.pixelSize: 12
                            color: Style.textSecondary
                        }

                        Text {
                            text: "OpenAI, Anthropic, Google"
                            font.pixelSize: 12
                            font.bold: true
                            color: Style.primary
                        }
                    }
                }
            }

            // Getting Started
            HelpSection {
                title: "Getting Started"

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    StepItem {
                        stepNumber: "1"
                        title: "Add API Keys"
                        description: "Go to AI Setup and configure your preferred AI providers. Enter API keys for services like OpenAI, Anthropic, Google, and others."
                    }

                    StepItem {
                        stepNumber: "2"
                        title: "Create Agents"
                        description: "Set up agents by combining a provider with a model and optional parameters. Agents are the building blocks for reports and chat."
                    }

                    StepItem {
                        stepNumber: "3"
                        title: "Generate Reports or Chat"
                        description: "Use New Report to generate AI-powered analysis using multiple agents, or open Chat for interactive conversations with any provider."
                    }
                }
            }

            // Concepts
            HelpSection {
                title: "Concepts"

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    ConceptItem {
                        term: "Provider"
                        definition: "An AI service such as OpenAI, Anthropic, Google, Mistral, Groq, and others. Each provider has its own API key, endpoint, and supported models. 28 of 31 providers are OpenAI-compatible."
                        accentColor: Style.primary
                    }

                    ConceptItem {
                        term: "Agent"
                        definition: "A configured combination of a provider and model with optional parameters like temperature, max tokens, and system prompt. Agents inherit API key, model, and endpoint from their provider when fields are left empty."
                        accentColor: Style.success
                    }

                    ConceptItem {
                        term: "Flock"
                        definition: "A group of agents that work together on a report. When a flock is selected for report generation, all its member agents contribute their responses to the final report."
                        accentColor: Style.warning
                    }

                    ConceptItem {
                        term: "Swarm"
                        definition: "A group of provider and model pairs. Unlike flocks which reference agents, swarms directly specify provider-model combinations for broader coverage."
                        accentColor: Style.info
                    }

                    ConceptItem {
                        term: "Parameters"
                        definition: "Reusable parameter presets including temperature, max tokens, top P, and other settings. Multiple parameter presets can be merged; later non-null values win, and booleans are sticky true."
                        accentColor: "#E056A0"
                    }
                }
            }

            // Keyboard Shortcuts
            HelpSection {
                title: "Keyboard Shortcuts"

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 8

                    ShortcutRow { keys: "Enter"; action: "Send message in chat" }
                    ShortcutRow { keys: "Ctrl+N"; action: "New chat session" }
                    ShortcutRow { keys: "Ctrl+,"; action: "Open Settings" }
                    ShortcutRow { keys: "Ctrl+Q"; action: "Quit application" }
                    ShortcutRow { keys: "Escape"; action: "Close dialog / cancel" }
                }
            }

            // Export/Import
            HelpSection {
                title: "Export / Import"

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 8

                    Text {
                        text: "Configuration can be exported and imported across all platforms using a shared JSON format."
                        font.pixelSize: 13
                        color: Style.textPrimary
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                    }

                    RowLayout {
                        spacing: 8

                        Text {
                            text: "Current format version:"
                            font.pixelSize: 12
                            color: Style.textSecondary
                        }

                        Rectangle {
                            width: versionLabel.implicitWidth + 12
                            height: 22
                            radius: 4
                            color: Style.surfaceVariant

                            Text {
                                id: versionLabel
                                anchors.centerIn: parent
                                text: "v21"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.primary
                            }
                        }
                    }

                    Text {
                        text: "Import accepts versions 11 through 21. The export includes all provider configurations, agents, flocks, swarms, parameter presets, prompts, and system prompts."
                        font.pixelSize: 13
                        color: Style.textPrimary
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                    }

                    Text {
                        text: "Cross-platform compatibility: Exports from Android, macOS, Linux, iOS, or Windows can be imported on any other platform."
                        font.pixelSize: 13
                        color: Style.textSecondary
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                        font.italic: true
                    }
                }
            }

            Item { Layout.preferredHeight: 40 }
        }
    }

    // Inline components

    component HelpSection: Rectangle {
        property string title
        default property alias content: sectionContent.children

        Layout.fillWidth: true
        implicitHeight: sectionLayout.implicitHeight + 32
        color: Style.cardBackground
        border.color: Style.cardBorder
        border.width: 1
        radius: 8

        ColumnLayout {
            id: sectionLayout
            anchors.fill: parent
            anchors.margins: 16
            spacing: 12

            Text {
                text: title
                font.pixelSize: 16
                font.bold: true
                color: Style.textPrimary
            }

            ColumnLayout {
                id: sectionContent
                Layout.fillWidth: true
                spacing: 8
            }
        }
    }

    component StepItem: RowLayout {
        property string stepNumber
        property string title
        property string description

        Layout.fillWidth: true
        spacing: 12

        Rectangle {
            Layout.preferredWidth: 32
            Layout.preferredHeight: 32
            radius: 16
            color: Style.primary
            Layout.alignment: Qt.AlignTop

            Text {
                anchors.centerIn: parent
                text: stepNumber
                font.pixelSize: 14
                font.bold: true
                color: "#FFFFFF"
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4

            Text {
                text: title
                font.pixelSize: 14
                font.bold: true
                color: Style.textPrimary
            }

            Text {
                text: description
                font.pixelSize: 12
                color: Style.textSecondary
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }
        }
    }

    component ConceptItem: RowLayout {
        property string term
        property string definition
        property color accentColor: Style.primary

        Layout.fillWidth: true
        spacing: 12

        Rectangle {
            Layout.preferredWidth: conceptLabel.implicitWidth + 16
            Layout.preferredHeight: 26
            radius: 4
            color: Qt.rgba(accentColor.r, accentColor.g, accentColor.b, 0.15)
            Layout.alignment: Qt.AlignTop

            Text {
                id: conceptLabel
                anchors.centerIn: parent
                text: term
                font.pixelSize: 12
                font.bold: true
                color: accentColor
            }
        }

        Text {
            text: definition
            font.pixelSize: 12
            color: Style.textSecondary
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }
    }

    component ShortcutRow: RowLayout {
        property string keys
        property string action

        Layout.fillWidth: true
        spacing: 12

        Rectangle {
            Layout.preferredWidth: Math.max(keyLabel.implicitWidth + 16, 80)
            Layout.preferredHeight: 26
            radius: 4
            color: Style.fieldBackground
            border.color: Style.fieldBorder
            border.width: 1

            Text {
                id: keyLabel
                anchors.centerIn: parent
                text: keys
                font.pixelSize: 11
                font.family: "monospace"
                font.bold: true
                color: Style.textPrimary
            }
        }

        Text {
            text: action
            font.pixelSize: 12
            color: Style.textSecondary
            Layout.fillWidth: true
        }
    }
}
