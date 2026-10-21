Pod::Spec.new do |s|
  s.name = 'SalmonNativeMap'
  s.version = '1.0.0'
  s.summary = 'Native Naver map behind the Salmonbus web interface'
  s.description = s.summary
  s.license = { :type => 'MIT' }
  s.author = 'Salmonbus'
  s.homepage = 'https://github.com/woowacourse-teams/2026-salmonbus'
  s.platforms = { :ios => '16.0' }
  s.source = { :git => s.homepage }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.dependency 'NMapsMap', '3.24.0'
  s.source_files = '**/*.{h,m,mm,swift}'
  s.resources = 'Resources/*.png'
  s.swift_version = '5.9'
end
