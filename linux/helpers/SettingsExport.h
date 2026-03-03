// SettingsExport.h - Export/import settings in v21 Android-compatible format (Linux/Qt6 port)
// Uses QFileDialog for save/open dialogs.

#pragma once

#include <QObject>
#include <functional>

#include "viewmodels/SettingsModels.h"
#include "helpers/SettingsPreferences.h"

namespace SettingsExport {

// Export full settings to a JSON file (opens save dialog)
void exportSettings(const Settings &aiSettings, const GeneralSettings &generalSettings);

// Import settings from a JSON file (opens open dialog)
// callback receives (Settings*, GeneralSettings*) - nullptr if import failed or cancelled
void importSettings(std::function<void(Settings*, GeneralSettings*)> callback);

// Export only API keys
void exportApiKeys(const Settings &aiSettings, const GeneralSettings &generalSettings);

// Import only API keys (merge into existing settings)
void importApiKeys(Settings &currentSettings, GeneralSettings &currentGeneralSettings,
                   std::function<void(bool success)> callback);

} // namespace SettingsExport
