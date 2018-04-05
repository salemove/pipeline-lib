Bundler.require :development

guard 'shell' do
  watch(/^README\.adoc$/) do |files|
    Asciidoctor.convert_file(files[0])
  end
end
