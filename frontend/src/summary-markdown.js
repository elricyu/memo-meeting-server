import MarkdownIt from 'markdown-it'
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false })
markdown.renderer.rules.image = (tokens,index) => markdown.utils.escapeHtml(tokens[index].content)
const linkOpen = markdown.renderer.rules.link_open
markdown.renderer.rules.link_open = (tokens,index,options,env,renderer) => {
  tokens[index].attrSet('target','_blank'); tokens[index].attrSet('rel','noopener noreferrer')
  return linkOpen ? linkOpen(tokens,index,options,env,renderer) : renderer.renderToken(tokens,index,options)
}
export function renderSummary(text) { return markdown.render(text || '') }
