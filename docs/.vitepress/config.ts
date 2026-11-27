import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'en-US',
  title: 'hometty',
  description: 'How to use and configure hometty.',
  base: '/hometty/',
  appearance: 'dark',
  head: [
    ['meta', { name: 'theme-color', content: '#282a36' }],
    ['link', { rel: 'icon', type: 'image/png', sizes: '32x32', href: '/hometty/favicon.png' }],
    ['link', { rel: 'icon', type: 'image/svg+xml', sizes: 'any', href: '/hometty/favicon.svg' }],
  ],
  themeConfig: {
    siteTitle: 'hometty / docs',
    nav: [
      { text: 'Guide', link: '/getting-started' },
      { text: 'Commands', link: '/reference/commands' },
      { text: 'Get the app', link: 'https://play.google.com/store/apps/details?id=io.luftaquila.hometty' },
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Overview', link: '/' },
          { text: 'Getting started', link: '/getting-started' },
          { text: 'Apps & aliases', link: '/apps-and-aliases' },
          { text: 'Terminal & shell', link: '/terminal' },
          { text: 'Appearance', link: '/appearance' },
          { text: 'Status lines', link: '/status-lines' },
          { text: 'Notifications', link: '/notifications' },
          { text: 'Weather, RSS & tools', link: '/tools' },
        ],
      },
      {
        text: 'Reference',
        items: [
          {
            text: 'Commands',
            link: '/reference/commands',
            collapsed: false,
            items: [
              { text: 'Apps', link: '/reference/commands/apps' },
              { text: 'People', link: '/reference/commands/people' },
              { text: 'Device', link: '/reference/commands/device' },
              { text: 'Tools', link: '/reference/commands/tools' },
              { text: 'Launcher', link: '/reference/commands/launcher' },
            ],
          },
          { text: 'Settings', link: '/reference/settings' },
          { text: 'Troubleshooting & backup', link: '/troubleshooting' },
          { text: 'Privacy', link: '/privacy' },
        ],
      },
    ],
    search: { provider: 'local' },
    outline: { level: [2, 3], label: 'On this page' },
    socialLinks: [{ icon: 'github', link: 'https://github.com/luftaquila/hometty' }],
  },
})
