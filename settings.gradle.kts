rootProject.name = "php-tools"

// `core` вшивается в каждый плагин при сборке и отдельно не публикуется.
include(":core", ":phpstan", ":psalm", ":php-cs-fixer")
