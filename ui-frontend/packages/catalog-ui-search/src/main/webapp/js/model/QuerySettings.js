/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/

const Backbone = require('backbone')
const properties = require('../properties.js')

module.exports = Backbone.Model.extend({
  defaults() {
    const hasDefaultSources =
      properties.defaultSources && properties.defaultSources.length > 0
    const sources = hasDefaultSources ? properties.defaultSources : undefined

    return {
      type: 'text',
      sources,
      federation: sources ? 'selected' : 'enterprise',
      sorts: [
        {
          attribute: 'modified',
          direction: 'descending',
        },
      ],
      template: undefined,
      spellcheck: false,
      phonetics: false,
    }
  },
  isTemplate(template) {
    if (this.get('defaultResultFormId') === template.id) {
      return true
    }
    if (this.get('template') !== undefined) {
      return this.get('template').id === template.id
    } else {
      return false
    }
  },
  isDefaultTemplate(template) {
    return this.isTemplate(template) && this.get('template').default
  },
})
