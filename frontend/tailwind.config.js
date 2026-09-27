/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    './app/**/*.{js,ts,jsx,tsx,mdx}',
    './pages/**/*.{js,ts,jsx,tsx,mdx}',
    './components/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        dark: {
          bg: '#0D1117',
          surface: '#161B22',
          border: '#30363D',
          hover: '#21262D',
          muted: '#8B949E',
        },
        brand: {
          primary: '#58A6FF',
          accent: '#A371F7',
          success: '#3FB950',
          warning: '#D29922',
          danger: '#F85149',
        }
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      }
    },
  },
  plugins: [],
}
