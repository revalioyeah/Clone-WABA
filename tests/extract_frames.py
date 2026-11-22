"""Prepare replay fixtures from the user's three recordings; needs ffmpeg/ffprobe/Pillow."""
import argparse
import json
from pathlib import Path
import subprocess
from PIL import Image
parser = argparse.ArgumentParser()
for name in ['giska', 'ujang', 'sumi']:
    parser.add_argument('--' + name, required=True)
parser.add_argument("--ujang-trial")
args = parser.parse_args()
out = Path('tests/frames')
out.mkdir(parents=True, exist_ok=True)
for name, source in [('01-video1', args.giska), ('video5', args.ujang), ('video4', args.sumi)]:
    duration = float(json.loads(subprocess.check_output(['ffprobe', '-v', 'quiet', '-show_format', '-of', 'json', source]))['format']['duration'])
    for i in range(8):
        path = out / f'{name}{i}.png'
        subprocess.run(['ffmpeg', '-v', 'error', '-ss', str(duration*i/8), '-i', source, '-frames:v', '1', '-y', str(path)], check=True)
        Image.open(path).resize((1170, 540)).save(out / f'{name}{i}_half.jpg', quality=80)
subprocess.run(['ffmpeg', '-v', 'error', '-i', args.ujang, '-vf', 'fps=12,scale=1170:540', '-y', str(out / 'video5_%03d.jpg')], check=True)

if args.ujang_trial:
    trial = Path('tests/trial2')
    trial.mkdir(parents=True, exist_ok=True)
    subprocess.run(['ffmpeg', '-v', 'error', '-i', args.ujang_trial, '-vf', 'fps=12,scale=1170:540', '-y', str(trial / 'ujang_%03d.jpg')], check=True)
