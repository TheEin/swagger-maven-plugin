import django
from django import template
from django.conf import settings

settings.configure()
django.setup()


def renderTemplate(t, ctx):
    t = template.Template(t)
    c = template.Context(ctx)
    return t.render(c)
