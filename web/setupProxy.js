const { createProxyMiddleware } = require('http-proxy-middleware');

const express = require('express')
const router = express.Router()

const environmentVariable = (variableName) => {
  const value = process.env[variableName]
  if (!value) {
      console.error(`Error: ${variableName} environment variable is not defined.`)
      console.error(`Please set ${variableName} and restart the application.`)
      process.exit(1)
  }
  return value
}

const marquezHost = environmentVariable("MARQUEZ_HOST")
const marquezPort = environmentVariable("MARQUEZ_PORT")
const targetUrl = `http://${marquezHost}:${marquezPort}`

console.log(`Configuring proxy to: ${targetUrl}`)

const app = express()
const path = __dirname + '/dist'

const port = environmentVariable("WEB_PORT")

// Simple request logger
app.use((req, res, next) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`)
  next()
})

// Proxy middleware MUST come before static middleware
// Using filter function for http-proxy-middleware v3
app.use(createProxyMiddleware({
  target: targetUrl,
  changeOrigin: true,
  pathFilter: ['/api/v1', '/api/v2beta'],
  logLevel: 'debug'
}))

// Serve static files with index.html support
const staticOptions = {
  index: 'index.html'
}

app.use('/', express.static(path, staticOptions))
app.use('/jobs', express.static(path, staticOptions))
app.use('/datasets', express.static(path, staticOptions))
app.use('/events', express.static(path, staticOptions))
app.use('/lineage/:type/:namespace/:name', express.static(path, staticOptions))
app.use('/datasets/column-level/:namespace/:name', express.static(path, staticOptions))

router.get('/healthcheck', function (req, res) {
  res.send('OK')
})

app.use(router)

app.listen(port, function() {
  console.log(`App listening on port ${port}!`)
})
