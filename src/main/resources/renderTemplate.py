import django
from django.conf import settings
from django import template

settings.configure()
django.setup()

def renderTemplate(t, ctx):
    t = template.Template(t)
    c = template.Context(ctx)
    return t.render(c)
