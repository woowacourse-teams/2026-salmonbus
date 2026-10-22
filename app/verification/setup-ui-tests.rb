#!/usr/bin/env ruby
# frozen_string_literal: true

# Reapply after Expo prebuild: the generated ios/ directory stays untracked.
# Usage: ruby app/verification/setup-ui-tests.rb [path/to/app.xcodeproj]
begin
  require 'xcodeproj'
rescue LoadError
  abort 'The xcodeproj Ruby gem is required. Install it or set GEM_HOME/GEM_PATH before running this script.'
end
require 'pathname'

abort 'Usage: setup-ui-tests.rb [path/to/app.xcodeproj]' if ARGV.length > 1

project_path = Pathname.new(ARGV.first || File.expand_path('../ios/app.xcodeproj', __dir__)).expand_path
abort "Missing generated project: #{project_path}. Run Expo prebuild first." unless project_path.directory?

project = Xcodeproj::Project.open(project_path)
app_target = project.targets.find { |target| target.name == 'app' }
abort "The project does not contain the app target: #{project_path}" unless app_target

target_name = 'NativeMapVerification'
test_target = project.targets.find { |target| target.name == target_name }
if test_target && test_target.symbol_type != :ui_test_bundle
  abort "Refusing to change existing non-UI-test target #{target_name}."
end
test_target ||= project.new_target(:ui_test_bundle, target_name, :ios, '16.4', nil, :swift)

test_target.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    'TEST_TARGET_NAME' => app_target.name,
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.salmonbus.app.NativeMapVerification',
    'PRODUCT_NAME' => '$(TARGET_NAME)',
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'SWIFT_VERSION' => '5.0',
    'CODE_SIGNING_ALLOWED' => 'NO',
    'TARGETED_DEVICE_FAMILY' => '1',
    'IPHONEOS_DEPLOYMENT_TARGET' => '16.4',
    # Swift's import XCTest links the framework using Xcode's testing paths.
    'ENABLE_TESTING_SEARCH_PATHS' => 'YES',
    'LD_RUNPATH_SEARCH_PATHS' => [
      '$(inherited)', '@executable_path/Frameworks', '@loader_path/Frameworks'
    ]
  )
end
test_target.add_dependency(app_target) unless test_target.dependencies.any? { |dependency| dependency.target == app_target }

target_attributes = project.root_object.attributes['TargetAttributes'] ||= {}
verification_attributes = target_attributes[test_target.uuid] ||= {}
verification_attributes['TestTargetID'] = app_target.uuid

source_path = Pathname.new(File.expand_path('NativeMapInteractionTests.swift', __dir__))
source_reference_path = source_path.relative_path_from(project_path.dirname).to_s
source_reference = project.files.find do |file|
  file.source_tree == 'SOURCE_ROOT' && file.path == source_reference_path
end
unless source_reference
  group = project.main_group.find_subpath('Native Map Verification', true)
  source_reference = group.new_file(source_reference_path, 'SOURCE_ROOT')
end
unless test_target.source_build_phase.files_references.include?(source_reference)
  test_target.source_build_phase.add_file_reference(source_reference)
end

project.save

# Only this script's scheme is regenerated; the app scheme and other targets stay intact.
scheme = Xcodeproj::XCScheme.new
scheme.configure_with_targets(app_target, test_target, :launch_target => true)
scheme.test_action.build_configuration = 'Debug'
scheme.save_as(project_path, target_name, true)

puts "Configured #{target_name} in #{project_path}"
puts "Source: #{source_reference_path}"
