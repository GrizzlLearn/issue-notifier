const path = require('path');
const TerserPlugin = require('terser-webpack-plugin');

module.exports = {
  entry: {
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
  optimization: {
    minimizer: [
      // extractComments: false — не генерировать *.js.LICENSE.txt рядом с бандлом (они попадали бы в JAR)
      new TerserPlugin({ extractComments: false }),
    ],
  },
};
