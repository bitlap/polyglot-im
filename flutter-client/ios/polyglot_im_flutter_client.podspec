Pod::Spec.new do |s|
  s.name = 'polyglot_im_flutter_client'
  s.version = '0.1.1'
  s.summary = 'Polyglot IM iOS APNs adapter'
  s.homepage = 'https://github.com/bitlap/polyglot-im'
  s.license = { :type => 'Apache-2.0' }
  s.author = { 'bitlap' => 'https://github.com/bitlap' }
  s.source = { :path => '.' }
  s.source_files = 'polyglot_im_flutter_client/Sources/polyglot_im_flutter_client/**/*.swift'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'
  s.swift_version = '5.0'
end
