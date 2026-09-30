const fs = require('fs');
const path = require('path');
const webpack = require('webpack');
const TerserPlugin = require('terser-webpack-plugin');

// Ключ плагина нужен в JS: WRM.require принимает только полный ключ ресурса.
// Источник истины — pom.xml (atlassian.plugin.key). При сборке через atlas-mvn
// его передаёт frontend-maven-plugin; при ручном `npm run dev` читаем из pom.
// ponytail: regexp по pom вместо XML-парсера — формат координат меняться не будет
function pluginKeyFromPom() {
  const pom = fs.readFileSync(path.resolve(__dirname, '../../../pom.xml'), 'utf8');
  const head = pom.slice(0, pom.indexOf('</version>'));
  const groupId = head.match(/<groupId>([^<]+)<\/groupId>/)[1];
  const artifactId = head.match(/<artifactId>([^<]+)<\/artifactId>/)[1];
  return `${groupId}.${artifactId}`;
}

const PLUGIN_KEY = process.env.PLUGIN_KEY || pluginKeyFromPom();

module.exports = {
  entry: {
    // грузится на каждой странице Jira — держим крошечным, он только
    // подтягивает user-settings по клику (см. src/user/nav.js)
    'user-nav': './src/user/nav.js',
    'user-settings': './src/user/index.jsx',
    'admin-settings': './src/admin/index.jsx',
  },
  output: {
    // .min.js — чтобы AMPS не пытался повторно минифицировать Closure Compiler'ом
    // (он не понимает ES2020+ синтаксис вроде `??`, который оставляет Terser)
    path: path.resolve(__dirname, '../resources/js'),
    filename: '[name].min.js',
  },
  module: {
    rules: [
      {
        test: /\.jsx?$/,
        exclude: /node_modules/,
        use: 'babel-loader',
      },
    ],
  },
  resolve: {
    extensions: ['.js', '.jsx'],
  },
  plugins: [
    new webpack.DefinePlugin({ __PLUGIN_KEY__: JSON.stringify(PLUGIN_KEY) }),
  ],
  optimization: {
    minimizer: [
      // extractComments: false — не генерировать *.js.LICENSE.txt рядом с бандлом (они попадали бы в JAR)
      new TerserPlugin({ extractComments: false }),
    ],
  },
};
